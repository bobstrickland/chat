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

    final Map<String, ConversationMeta> metaByConv = new LinkedHashMap<>();

    @Override
    public void ensureDirectConversation(String conversationId, String a, String b) {
      ensureCalls++;
      membersByConv.computeIfAbsent(conversationId, k -> List.of(a, b));
    }

    @Override
    public void createGroup(String conversationId, String name, String createdBy, List<String> members) {
      membersByConv.put(conversationId, List.copyOf(members));
      metaByConv.put(conversationId, new ConversationMeta("group", name));
    }

    @Override
    public ConversationMeta meta(String conversationId) {
      return metaByConv.get(conversationId);
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

    @Override
    public List<String> userConversations(String userId) {
      List<String> ids = new ArrayList<>();
      for (var e : membersByConv.entrySet()) {
        if (e.getValue().contains(userId)) {
          ids.add(e.getKey());
        }
      }
      return ids;
    }

    @Override
    public Message lastMessage(String conversationId) {
      Message last = null;
      for (Message m : messages) {
        if (m.conversationId().equals(conversationId)) {
          last = m;
        }
      }
      return last;
    }

    final Map<String, Receipt> receiptItems = new LinkedHashMap<>(); // key: conv|kind|user

    @Override
    public void upsertReceipt(String conversationId, String kind, String userId, String position) {
      String key = conversationId + "|" + kind + "|" + userId;
      Receipt existing = receiptItems.get(key);
      if (existing == null || existing.position().compareTo(position) < 0) {
        receiptItems.put(key, new Receipt(userId, kind, position));
      }
    }

    @Override
    public List<Receipt> receipts(String conversationId) {
      List<Receipt> out = new ArrayList<>();
      for (var e : receiptItems.entrySet()) {
        if (e.getKey().startsWith(conversationId + "|")) {
          out.add(e.getValue());
        }
      }
      return out;
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
  void directPeerIsTheOtherParticipant() {
    String conv = MessagingService.directConversationId("alice", "bob");
    assertEquals("bob", MessagingService.directPeer(conv, "alice"));
    assertEquals("alice", MessagingService.directPeer(conv, "bob"));
  }

  @Test
  void listsConversationsMostRecentFirstWithPeerAndPreview() {
    FakeRepo repo = new FakeRepo();
    MessagingService svc = new MessagingService(repo, new FakePublisher());
    svc.sendDirect("alice", "bob", "hi bob");
    svc.sendDirect("alice", "carol", "hi carol"); // newer

    List<ConversationSummary> list = svc.listConversations("alice");
    assertEquals(2, list.size());
    // carol's conversation is most recent -> first
    assertEquals("carol", list.get(0).peerId());
    assertEquals("hi carol", list.get(0).lastMessage().body());
    assertEquals("bob", list.get(1).peerId());
  }

  @Test
  void createGroupIncludesCreatorAndPersistsMeta() {
    FakeRepo repo = new FakeRepo();
    String conv = new MessagingService(repo, new FakePublisher())
        .createGroup("alice", "Team", List.of("bob", "carol"));
    assertTrue(conv.startsWith("grp#"));
    assertEquals(List.of("alice", "bob", "carol"), repo.membersByConv.get(conv));
    assertEquals("Team", repo.metaByConv.get(conv).name());
  }

  @Test
  void createGroupRejectsBlankNameAndSoleMember() {
    MessagingService svc = new MessagingService(new FakeRepo(), new FakePublisher());
    assertThrows(IllegalArgumentException.class, () -> svc.createGroup("alice", "  ", List.of("bob")));
    // Only the creator (self-listed) -> not enough members.
    assertThrows(IllegalArgumentException.class, () -> svc.createGroup("alice", "Solo", List.of("alice")));
  }

  @Test
  void sendToGroupRequiresMembershipAndFansOutViaMessageSent() {
    FakeRepo repo = new FakeRepo();
    FakePublisher pub = new FakePublisher();
    MessagingService svc = new MessagingService(repo, pub);
    String conv = svc.createGroup("alice", "Team", List.of("bob", "carol"));

    Message m = svc.sendToConversation("bob", conv, "hi team");
    assertEquals(conv, m.conversationId());
    assertEquals(1, pub.published.size());

    // A non-member cannot send.
    assertThrows(IllegalStateException.class, () -> svc.sendToConversation("mallory", conv, "sneak"));
  }

  @Test
  void sendToUnknownGroupIsNotFound() {
    MessagingService svc = new MessagingService(new FakeRepo(), new FakePublisher());
    assertThrows(IllegalStateException.class, () -> svc.sendToConversation("alice", "grp#missing", "x"));
  }

  @Test
  void cannotSendToADirectYouAreNotPartOf() {
    MessagingService svc = new MessagingService(new FakeRepo(), new FakePublisher());
    String someoneElsesDm = MessagingService.directConversationId("bob", "carol");
    assertThrows(IllegalStateException.class, () -> svc.sendToConversation("alice", someoneElsesDm, "x"));
  }

  @Test
  void listConversationsLabelsGroupsWithNameAndDirectsWithPeer() {
    FakeRepo repo = new FakeRepo();
    MessagingService svc = new MessagingService(repo, new FakePublisher());
    svc.sendDirect("alice", "bob", "hi"); // direct
    String group = svc.createGroup("alice", "Team", List.of("bob", "carol"));
    svc.sendToConversation("alice", group, "hey team"); // group (newer)

    List<ConversationSummary> list = svc.listConversations("alice");
    ConversationSummary top = list.get(0); // group is most recent
    assertEquals("group", top.type());
    assertEquals("Team", top.name());
    ConversationSummary direct = list.stream().filter(c -> c.type().equals("direct")).findFirst().orElseThrow();
    assertEquals("bob", direct.peerId());
  }

  @Test
  void recordReceiptStoresPositionAndReturnsMembersForFanout() {
    FakeRepo repo = new FakeRepo();
    MessagingService svc = new MessagingService(repo, new FakePublisher());
    String conv = svc.createGroup("alice", "Team", List.of("bob", "carol"));

    List<String> members = svc.recordReceipt("bob", conv, "read", "2026-01-01T00:00:05Z");
    assertEquals(List.of("alice", "bob", "carol"), members);
    assertEquals("2026-01-01T00:00:05Z", repo.receiptItems.get(conv + "|read|bob").position());
  }

  @Test
  void receiptPositionOnlyMovesForward() {
    FakeRepo repo = new FakeRepo();
    MessagingService svc = new MessagingService(repo, new FakePublisher());
    String conv = svc.createGroup("alice", "Team", List.of("bob"));
    svc.recordReceipt("bob", conv, "read", "2026-01-01T00:00:10Z");
    svc.recordReceipt("bob", conv, "read", "2026-01-01T00:00:05Z"); // older, ignored
    assertEquals("2026-01-01T00:00:10Z", repo.receiptItems.get(conv + "|read|bob").position());
  }

  @Test
  void recordReceiptRejectsBadKindAndNonMembers() {
    FakeRepo repo = new FakeRepo();
    MessagingService svc = new MessagingService(repo, new FakePublisher());
    String conv = svc.createGroup("alice", "Team", List.of("bob"));
    assertThrows(IllegalArgumentException.class, () -> svc.recordReceipt("bob", conv, "seen", "2026-01-01T00:00:00Z"));
    assertThrows(IllegalStateException.class, () -> svc.recordReceipt("mallory", conv, "read", "2026-01-01T00:00:00Z"));
  }

  @Test
  void doesNotPublishWhenValidationFails() {
    FakePublisher pub = new FakePublisher();
    MessagingService svc = new MessagingService(new FakeRepo(), pub);
    assertThrows(IllegalArgumentException.class, () -> svc.sendDirect("alice", "alice", "hi"));
    assertTrue(pub.published.isEmpty(), "nothing published on a rejected send");
  }
}
