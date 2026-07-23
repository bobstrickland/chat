package dev.rstrickland.chat.messaging.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagingServiceTest {

  /** Minimal in-memory ConversationRepository. */
  static final class FakeRepo implements ConversationRepository {
    final Map<String, List<String>> membersByConv = new LinkedHashMap<>();
    final List<Message> messages = new ArrayList<>();
    int ensureCalls = 0;

    @Override
    public void ensureDirectConversation(String conversationId, String a, String b) {
      ensureCalls++;
      membersByConv.computeIfAbsent(conversationId, k -> List.of(a, b));
    }

    @Override
    public void saveMessage(Message m) {
      messages.add(m);
    }

    @Override
    public List<Message> listMessages(String conversationId, int limit) {
      return messages.stream().filter(m -> m.conversationId().equals(conversationId)).toList();
    }

    @Override
    public List<String> members(String conversationId) {
      return membersByConv.getOrDefault(conversationId, List.of());
    }
  }

  static final class FakePublisher implements MessageEventPublisher {
    final List<Message> published = new ArrayList<>();

    @Override
    public void messageSent(Message m) {
      published.add(m);
    }
  }

  @Test
  void directConversationIdIsSymmetric() {
    assertEquals(
        MessagingService.directConversationId("alice", "bob"),
        MessagingService.directConversationId("bob", "alice"));
  }

  @Test
  void sendPersistsThenPublishesTheSameMessage() {
    FakeRepo repo = new FakeRepo();
    FakePublisher pub = new FakePublisher();
    Message m = new MessagingService(repo, pub).sendDirect("alice", "bob", "  hi bob  ");

    assertEquals(1, repo.messages.size());
    assertEquals(1, pub.published.size());
    assertEquals(m.messageId(), pub.published.get(0).messageId());
    assertEquals("alice", m.senderId());
    assertEquals("hi bob", m.body(), "body is trimmed");
    assertEquals(MessagingService.directConversationId("alice", "bob"), m.conversationId());
  }

  @Test
  void eachSendGetsAUniqueMessageId() {
    FakeRepo repo = new FakeRepo();
    MessagingService svc = new MessagingService(repo, new FakePublisher());
    Message a = svc.sendDirect("alice", "bob", "one");
    Message b = svc.sendDirect("alice", "bob", "two");
    assertNotEquals(a.messageId(), b.messageId());
  }

  @Test
  void rejectsEmptyBodyBlankRecipientAndSelfSend() {
    MessagingService svc = new MessagingService(new FakeRepo(), new FakePublisher());
    assertThrows(IllegalArgumentException.class, () -> svc.sendDirect("alice", "bob", "  "));
    assertThrows(IllegalArgumentException.class, () -> svc.sendDirect("alice", "", "hi"));
    assertThrows(IllegalArgumentException.class, () -> svc.sendDirect("alice", "alice", "hi"));
  }

  @Test
  void doesNotPublishWhenValidationFails() {
    FakePublisher pub = new FakePublisher();
    MessagingService svc = new MessagingService(new FakeRepo(), pub);
    assertThrows(IllegalArgumentException.class, () -> svc.sendDirect("alice", "alice", "hi"));
    assertTrue(pub.published.isEmpty(), "nothing published on a rejected send");
  }
}
