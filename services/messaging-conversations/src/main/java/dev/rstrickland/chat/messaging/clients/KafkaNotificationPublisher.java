package dev.rstrickland.chat.messaging.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rstrickland.chat.messaging.core.Message;
import dev.rstrickland.chat.messaging.core.NotificationTrigger;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Publishes notification.trigger for an offline recipient, keyed by recipientId
 * (per-recipient ordering; a user's notifications stay in order). The event
 * carries the specific offline user plus a message preview, so Notification can
 * push without resolving membership.
 */
public final class KafkaNotificationPublisher implements NotificationTrigger {

  private final Producer<String, String> producer;
  private final String topic;
  private final ObjectMapper mapper = new ObjectMapper();

  public KafkaNotificationPublisher(Producer<String, String> producer, String topic) {
    this.producer = producer;
    this.topic = topic;
  }

  @Override
  public void offlineRecipient(String recipientId, Message m) {
    ObjectNode event = mapper.createObjectNode();
    event.put("recipientId", recipientId);
    event.put("conversationId", m.conversationId());
    event.put("messageId", m.messageId());
    event.put("senderId", m.senderId());
    event.put("body", m.body());
    event.put("sentAt", m.sentAt().toString());

    producer.send(
        new ProducerRecord<>(topic, recipientId, event.toString()),
        (metadata, ex) -> {
          if (ex != null) {
            System.err.println("[messaging] failed to publish " + topic + ": " + ex.getMessage());
          }
        });
  }
}
