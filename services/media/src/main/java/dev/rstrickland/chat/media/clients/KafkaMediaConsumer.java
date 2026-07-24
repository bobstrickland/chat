package dev.rstrickland.chat.media.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rstrickland.chat.media.core.MediaService;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * The media-processing WORKER: consumes media.uploaded and runs
 * MediaService.process (the ffmpeg/ImageIO transcode) off the request thread.
 * Runs on a daemon thread inside HttpServerMain; in AWS this is an
 * MSK/S3-triggered Lambda over the same MediaService.process.
 *
 * `auto.offset.reset=earliest` (unlike the messaging delivery consumer): a media
 * job must NOT be lost if the worker restarts mid-queue, and process() is
 * idempotent, so re-processing a pending item is safe.
 */
public final class KafkaMediaConsumer implements AutoCloseable {

  private final String brokers;
  private final String topic;
  private final String groupId;
  private final MediaService media;
  private final ObjectMapper mapper = new ObjectMapper();

  private volatile boolean running = false;
  private Thread thread;

  public KafkaMediaConsumer(String brokers, String topic, String groupId, MediaService media) {
    this.brokers = brokers;
    this.topic = topic;
    this.groupId = groupId;
    this.media = media;
  }

  public void start() {
    running = true;
    thread = new Thread(this::run, "media-uploaded-consumer");
    thread.setDaemon(true);
    thread.start();
  }

  private void run() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      System.out.println("[media] worker consuming " + topic);
      while (running) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> record : records) {
          try {
            String mediaId = mapper.readTree(record.value()).path("mediaId").asText(null);
            if (mediaId != null) {
              media.process(mediaId);
            }
          } catch (Exception e) {
            // A poison message must not stall the partition — log and move on.
            System.err.println("[media] worker failed on a record: " + e.getMessage());
          }
        }
      }
    } catch (Exception e) {
      if (running) {
        System.err.println("[media] worker crashed: " + e.getMessage());
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
