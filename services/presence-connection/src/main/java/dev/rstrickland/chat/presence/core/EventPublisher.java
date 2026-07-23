package dev.rstrickland.chat.presence.core;

/**
 * Emits presence transitions onto the event backbone (connection.state.changed).
 * Implemented by clients/ against Kafka/MSK; core depends only on this.
 */
public interface EventPublisher {

  /**
   * @param userId       whose presence changed
   * @param connectionId the connection that opened/closed
   * @param state        "connected" | "disconnected"
   * @param online       whether the user still has ANY active connection after
   *                     this change — i.e. the online/offline signal consumers
   *                     (Messaging, Notification) actually branch on
   */
  void connectionStateChanged(String userId, String connectionId, String state, boolean online);
}
