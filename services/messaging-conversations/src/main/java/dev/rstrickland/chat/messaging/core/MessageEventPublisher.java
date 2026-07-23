package dev.rstrickland.chat.messaging.core;

/**
 * Emits message.sent onto the event backbone. Implemented by clients/ against
 * Kafka/MSK. This is the fan-out point: delivery (real-time push), notification
 * (offline, Phase 5), and search indexing (Phase 9) all consume message.sent.
 */
public interface MessageEventPublisher {
  void messageSent(Message message);
}
