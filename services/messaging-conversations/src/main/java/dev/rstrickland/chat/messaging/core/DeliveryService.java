package dev.rstrickland.chat.messaging.core;

import java.util.List;

/**
 * Real-time delivery — pure logic, driven off a consumed message.sent event.
 *
 * Fan-out: for each conversation member — INCLUDING the sender's other devices
 * (Phase 7 multi-device sync) — look up their active connections and push. The
 * sending client dedups by messageId (it already showed the message locally), so
 * the originating tab ignores its own echo while the user's other tabs display
 * it. Generalizes to groups unchanged.
 *
 * A user is never sent a Notification (offline push) for their OWN message —
 * that would be nonsensical.
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
      boolean isSender = member.equals(message.senderId());
      List<String> memberConnections = connections.activeConnections(member);

      if (memberConnections.isEmpty()) {
        // Offline. Notify recipients only — never the sender about their own message.
        if (!isSender) {
          notificationTrigger.offlineRecipient(member, message);
        }
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
