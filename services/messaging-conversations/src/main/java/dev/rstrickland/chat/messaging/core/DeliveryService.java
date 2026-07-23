package dev.rstrickland.chat.messaging.core;

import java.util.List;

/**
 * Real-time delivery — pure logic, driven off a consumed message.sent event.
 *
 * Fan-out: for each conversation member other than the sender, look up their
 * active connections and push the message to each. This generalizes straight to
 * groups (Phase 6, more members) and multi-device (Phase 7, more connections per
 * member) with no change here — the member/connection sets just get larger.
 *
 * The sender's OWN other devices are intentionally skipped in Phase 4 (the
 * sending client shows its message locally). Sender multi-device echo is Phase 7.
 */
public final class DeliveryService {

  private final ConversationRepository repository;
  private final ConnectionLookup connections;
  private final ConnectionPusher pusher;
  private final NotificationTrigger notificationTrigger;
  private final PayloadWriter payloadWriter;

  /** Serializes a Message to the JSON frame pushed to clients. */
  public interface PayloadWriter {
    String toFrame(Message message);
  }

  public DeliveryService(
      ConversationRepository repository,
      ConnectionLookup connections,
      ConnectionPusher pusher,
      NotificationTrigger notificationTrigger,
      PayloadWriter payloadWriter) {
    this.repository = repository;
    this.connections = connections;
    this.pusher = pusher;
    this.notificationTrigger = notificationTrigger;
    this.payloadWriter = payloadWriter;
  }

  public void deliver(Message message) {
    String frame = payloadWriter.toFrame(message);
    List<String> members = repository.members(message.conversationId());

    for (String member : members) {
      if (member.equals(message.senderId())) {
        continue; // skip the sender (Phase 4)
      }

      List<String> memberConnections = connections.activeConnections(member);
      if (memberConnections.isEmpty()) {
        // Offline: no live socket to push to → hand off to Notification (Phase 5).
        notificationTrigger.offlineRecipient(member, message);
        continue;
      }
      for (String connectionId : memberConnections) {
        // A stale connection returns false and is simply skipped — do not let
        // one dead socket abort delivery to the member's other devices.
        pusher.push(connectionId, frame);
      }
    }
  }
}
