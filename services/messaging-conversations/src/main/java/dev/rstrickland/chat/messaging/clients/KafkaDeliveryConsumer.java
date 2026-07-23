package dev.rstrickland.chat.messaging.clients;

import dev.rstrickland.chat.messaging.core.DeliveryService;
import dev.rstrickland.chat.messaging.core.Message;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Consumes message.sent and runs real-time delivery. In the HttpServer/Fargate
 * adapter this runs as a background poll loop (started by HttpServerMain); in
 * AWS the same DeliveryService would be driven by an MSK-triggered Lambda
 * instead — the event source changes, the delivery logic doesn't.
 *
 * Runs on its own daemon thread so it doesn't block the HTTP server.
 */
public final class KafkaDeliveryConsumer implements AutoCloseable {

  private final String brokers;
  private final String topic;
  private final String groupId;
  private final DeliveryService delivery;
  private final MessageJson json = new MessageJson();

  private volatile boolean running = false;
  private Thread thread;

  public KafkaDeliveryConsumer(
      String brokers, String topic, String groupId, DeliveryService delivery) {
    this.brokers = brokers;
    this.topic = topic;
    this.groupId = groupId;
    this.delivery = delivery;
  }

  public void start() {
    running = true;
    thread = new Thread(this::run, "message-sent-consumer");
    thread.setDaemon(true);
    thread.start();
  }

  private void run() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      System.out.println("[messaging] delivery consumer subscribed to " + topic);
      while (running) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> record : records) {
          try {
            Message message = json.fromEvent(record.value());
            delivery.deliver(message);
          } catch (Exception e) {
            // A poison message must not stall the partition — log and move on.
            System.err.println("[messaging] delivery failed: " + e.getMessage());
          }
        }
      }
    } catch (Exception e) {
      if (running) {
        System.err.println("[messaging] delivery consumer crashed: " + e.getMessage());
      }
    }
  }

  @Override
  public void close() {
    running = false;
    if (thread != null) {
      thread.interrupt();
    }
  }
}
