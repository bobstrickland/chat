package dev.rstrickland.chat.media.clients;

import dev.rstrickland.chat.media.core.MediaEventPublisher;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Publishes media.uploaded, keyed by mediaId. Payload is a tiny JSON `{mediaId}`. */
public final class KafkaMediaPublisher implements MediaEventPublisher {

  private final Producer<String, String> producer;
  private final String topic;

  public KafkaMediaPublisher(Producer<String, String> producer, String topic) {
    this.producer = producer;
    this.topic = topic;
  }

  @Override
  public void mediaUploaded(String mediaId) {
    String value = "{\"mediaId\":\"" + mediaId + "\"}";
    producer.send(
        new ProducerRecord<>(topic, mediaId, value),
        (metadata, ex) -> {
          if (ex != null) {
            System.err.println("[media] failed to publish " + topic + ": " + ex.getMessage());
          }
        });
  }
}
