package dev.rstrickland.chat.messaging.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeliveryServiceTest {

  static Message msg(String conv, String sender, String body) {
    return new Message(conv, "m-" + body, sender, body, Instant.now());
  }

  /** Repo that only needs to answer members() for delivery. */
  static ConversationRepository repoWithMembers(String conv, List<String> members) {
    return new ConversationRepository() {
      public void ensureDirectConversation(String c, String a, String b) {}

      public void saveMessage(Message m) {}

      public List<Message> listMessages(String c, int limit) {
        return List.of();
      }

      public List<String> members(String c) {
        return c.equals(conv) ? members : List.of();
      }

      public List<String> userConversations(String userId) {
        return List.of();
      }

      public Message lastMessage(String c) {
        return null;
      }
    };
  }

  record Push(String connectionId, String frame) {}

  static final class RecordingPusher implements ConnectionPusher {
    final List<Push> pushes = new ArrayList<>();
    boolean pretendStale = false;

    public boolean push(String connectionId, String frame) {
      pushes.add(new Push(connectionId, frame));
      return !pretendStale;
    }
  }

  static final class RecordingNotifier implements NotificationTrigger {
    final List<String> offline = new ArrayList<>();

    public void offlineRecipient(String recipientId, Message message) {
      offline.add(recipientId);
    }
  }

  private static final NotificationTrigger NO_NOTIFY = (r, m) -> {};

  @Test
  void deliversToEveryRecipientConnectionButNotTheSender() {
    var repo = repoWithMembers("c1", List.of("alice", "bob"));
    // bob is on two devices; alice (the sender) also happens to have a connection.
    ConnectionLookup lookup =
        userId ->
            switch (userId) {
              case "bob" -> List.of("bob-web", "bob-phone");
              case "alice" -> List.of("alice-web");
              default -> List.of();
            };
    var pusher = new RecordingPusher();
    var delivery = new DeliveryService(repo, lookup, pusher, NO_NOTIFY, m -> "FRAME:" + m.body());

    delivery.deliver(msg("c1", "alice", "hello"));

    List<String> targets = pusher.pushes.stream().map(Push::connectionId).toList();
    assertEquals(List.of("bob-web", "bob-phone"), targets, "both of bob's devices, not alice's");
    assertTrue(pusher.pushes.stream().allMatch(p -> p.frame().equals("FRAME:hello")));
  }

  @Test
  void offlineRecipientYieldsNoPushesButTriggersNotification() {
    var repo = repoWithMembers("c1", List.of("alice", "bob"));
    ConnectionLookup noConnections = userId -> List.of();
    var pusher = new RecordingPusher();
    var notifier = new RecordingNotifier();
    new DeliveryService(repo, noConnections, pusher, notifier, m -> m.body())
        .deliver(msg("c1", "alice", "hi"));

    assertTrue(pusher.pushes.isEmpty(), "no live socket to push to");
    assertEquals(List.of("bob"), notifier.offline, "offline recipient handed off to Notification");
  }

  @Test
  void onlineRecipientDoesNotTriggerNotification() {
    var repo = repoWithMembers("c1", List.of("alice", "bob"));
    ConnectionLookup lookup = userId -> userId.equals("bob") ? List.of("bob-web") : List.of();
    var notifier = new RecordingNotifier();
    new DeliveryService(repo, lookup, new RecordingPusher(), notifier, m -> m.body())
        .deliver(msg("c1", "alice", "hi"));

    assertTrue(notifier.offline.isEmpty(), "online recipient gets a live push, not a notification");
  }

  @Test
  void aStaleConnectionDoesNotAbortRemainingPushes() {
    var repo = repoWithMembers("c1", List.of("alice", "bob"));
    ConnectionLookup lookup = userId -> userId.equals("bob") ? List.of("d1", "d2") : List.of();
    var pusher = new RecordingPusher();
    pusher.pretendStale = true; // every push reports "gone"
    var delivery = new DeliveryService(repo, lookup, pusher, NO_NOTIFY, m -> m.body());

    delivery.deliver(msg("c1", "alice", "hi"));

    // Both were attempted even though the first came back stale.
    assertEquals(2, pusher.pushes.size());
  }

  @Test
  void groupFanOutHitsAllMembersExceptSender() {
    var repo = repoWithMembers("g1", List.of("alice", "bob", "carol"));
    var counts = new java.util.HashMap<String, AtomicInteger>();
    ConnectionLookup lookup = userId -> List.of(userId + "-conn");
    ConnectionPusher pusher =
        (connectionId, frame) -> {
          counts.computeIfAbsent(connectionId, k -> new AtomicInteger()).incrementAndGet();
          return true;
        };
    new DeliveryService(repo, lookup, pusher, NO_NOTIFY, m -> m.body()).deliver(msg("g1", "alice", "hey all"));

    assertFalse(counts.containsKey("alice-conn"), "sender excluded");
    assertEquals(1, counts.get("bob-conn").get());
    assertEquals(1, counts.get("carol-conn").get());
  }
}
