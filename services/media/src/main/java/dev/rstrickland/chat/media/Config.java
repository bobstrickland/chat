package dev.rstrickland.chat.media;

import dev.rstrickland.chat.media.clients.DefaultMediaProcessor;
import dev.rstrickland.chat.media.clients.DynamoMediaRepository;
import dev.rstrickland.chat.media.clients.JwksTokenVerifier;
import dev.rstrickland.chat.media.clients.KafkaMediaConsumer;
import dev.rstrickland.chat.media.clients.KafkaMediaPublisher;
import dev.rstrickland.chat.media.clients.S3Storage;
import dev.rstrickland.chat.media.clients.TokenVerifier;
import dev.rstrickland.chat.media.core.MediaService;
import java.net.URI;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Single wiring point (config.js analogue). Plain constructor DI, no Spring. */
public final class Config {

  public final MediaService media;
  public final TokenVerifier verifier;
  public final KafkaMediaConsumer worker;
  public final int port;

  private Config(MediaService media, TokenVerifier verifier, KafkaMediaConsumer worker, int port) {
    this.media = media;
    this.verifier = verifier;
    this.worker = worker;
    this.port = port;
  }

  private static String env(String name, String fallback) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? fallback : v;
  }

  private static String require(String name) {
    String v = System.getenv(name);
    if (v == null || v.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return v;
  }

  public static Config fromEnv() {
    String region = env("AWS_REGION", "us-east-1");
    String table = env("MEDIA_METADATA_TABLE", "media-metadata-local");
    String dynamoEndpoint = System.getenv("DYNAMODB_ENDPOINT");
    String s3Internal = require("S3_ENDPOINT"); // minio:9000
    String s3Public = env("MEDIA_S3_PUBLIC_ENDPOINT", "http://localhost:9000"); // browser-facing
    String bucket = require("MEDIA_BUCKET");
    String s3Key = env("AWS_ACCESS_KEY_ID_S3", env("AWS_ACCESS_KEY_ID", "localminio"));
    String s3Secret = env("AWS_SECRET_ACCESS_KEY_S3", env("AWS_SECRET_ACCESS_KEY", "localminio123"));
    String jwksUrl = require("COGNITO_JWKS_URL");
    String brokers = require("KAFKA_BROKERS");
    String topic = env("TOPIC_MEDIA_UPLOADED", "media.uploaded");
    String workerGroup = env("MEDIA_WORKER_GROUP", "media-processor");
    int port = Integer.parseInt(env("PORT", "3000"));

    var dynamoBuilder = DynamoDbClient.builder().region(Region.of(region));
    if (dynamoEndpoint != null && !dynamoEndpoint.isBlank()) {
      dynamoBuilder.endpointOverride(URI.create(dynamoEndpoint));
    }
    DynamoDbClient dynamo = dynamoBuilder.build();

    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
    props.put(ProducerConfig.CLIENT_ID_CONFIG, "media");
    Producer<String, String> producer = new KafkaProducer<>(props);

    var repository = new DynamoMediaRepository(dynamo, table);
    var storage = new S3Storage(s3Internal, s3Public, region, bucket, s3Key, s3Secret);
    var processor = new DefaultMediaProcessor();
    var verifier = new JwksTokenVerifier(jwksUrl);
    var publisher = new KafkaMediaPublisher(producer, topic);
    var media = new MediaService(repository, storage, processor, publisher);
    var worker = new KafkaMediaConsumer(brokers, topic, workerGroup, media);

    return new Config(media, verifier, worker, port);
  }
}
