package dev.rstrickland.chat.messaging.clients;

import dev.rstrickland.chat.messaging.core.Message;
import dev.rstrickland.chat.messaging.core.MessageEventPublisher;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Publishes message.sent to Kafka/MSK, keyed by conversationId so all messages
 * in a conversation land on one partition and stay ordered — the ordering
 * guarantee downstream consumers rely on (CLAUDE.md Event Backbone).
 */
public final class KafkaMessagePublisher implements MessageEventPublisher {

  private final Producer<String, String> producer;
  private final String topic;
  private final MessageJson json = new MessageJson();

  public KafkaMessagePublisher(Producer<String, String> producer, String topic) {
    this.producer = producer;
    this.topic = topic;
  }

  @Override
  public void messageSent(Message m) {
    producer.send(
        new ProducerRecord<>(topic, m.conversationId(), json.toEvent(m)),
        (metadata, ex) -> {
          if (ex != null) {
            System.err.println("[messaging] failed to publish " + topic + ": " + ex.getMessage());
          }
        });
  }
}
