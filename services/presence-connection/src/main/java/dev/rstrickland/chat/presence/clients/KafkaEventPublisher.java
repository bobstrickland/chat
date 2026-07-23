package dev.rstrickland.chat.presence.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rstrickland.chat.presence.core.EventPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Publishes connection.state.changed to Kafka/MSK (Redpanda locally).
 *
 * Keyed by userId so all of a user's presence events land on one partition and
 * stay ordered per user — connection events aren't conversation-scoped, so
 * userId (not conversationId) is the right partition key here (cf. CLAUDE.md
 * Event Backbone: conversation-scoped events partition by conversationId).
 *
 * Local Redpanda is plaintext; MSK IAM auth is configured at the producer level
 * in Config, not here (CLAUDE.md: IAM-auth bugs won't surface until deployed).
 */
public final class KafkaEventPublisher implements EventPublisher {

  private final Producer<String, String> producer;
  private final String topic;
  private final ObjectMapper mapper = new ObjectMapper();

  public KafkaEventPublisher(Producer<String, String> producer, String topic) {
    this.producer = producer;
    this.topic = topic;
  }

  @Override
  public void connectionStateChanged(
      String userId, String connectionId, String state, boolean online) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("userId", userId);
    event.put("connectionId", connectionId);
    event.put("state", state);
    event.put("online", online);
    event.put("at", Instant.now().toString());

    String value;
    try {
      value = mapper.writeValueAsString(event);
    } catch (Exception e) {
      throw new RuntimeException("failed to serialize connection.state.changed", e);
    }

    // Fire-and-forget with a logging callback. A publish failure must not fail
    // the connection itself — the DynamoDB write already recorded presence;
    // the event is a downstream signal, best-effort.
    producer.send(
        new ProducerRecord<>(topic, userId, value),
        (metadata, ex) -> {
          if (ex != null) {
            System.err.println("[presence] failed to publish " + topic + ": " + ex.getMessage());
          }
        });
  }
}
