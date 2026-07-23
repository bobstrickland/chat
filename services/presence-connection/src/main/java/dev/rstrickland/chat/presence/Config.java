package dev.rstrickland.chat.presence;

import dev.rstrickland.chat.presence.adapters.WebSocketRouter;
import dev.rstrickland.chat.presence.clients.DynamoConnectionRepository;
import dev.rstrickland.chat.presence.clients.JwksTokenVerifier;
import dev.rstrickland.chat.presence.clients.KafkaEventPublisher;
import dev.rstrickland.chat.presence.clients.TokenVerifier;
import dev.rstrickland.chat.presence.core.PresenceService;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Single wiring point — the Java analogue of the Node services' config.js. core/
 * never constructs a client; it receives them here. No Spring/DI framework: this
 * is plain constructor wiring, which keeps cold-start and the dependency graph
 * obvious.
 */
public final class Config {

  public final PresenceService presence;
  public final TokenVerifier verifier;
  public final WebSocketRouter webSocketRouter;
  public final String internalApiKey;
  public final int port;

  private Config(
      PresenceService presence,
      TokenVerifier verifier,
      WebSocketRouter webSocketRouter,
      String internalApiKey,
      int port) {
    this.presence = presence;
    this.verifier = verifier;
    this.webSocketRouter = webSocketRouter;
    this.internalApiKey = internalApiKey;
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
    String table = env("PRESENCE_CONNECTIONS_TABLE", "presence-connections-local");
    String dynamoEndpoint = System.getenv("DYNAMODB_ENDPOINT"); // null in AWS
    String brokers = require("KAFKA_BROKERS");
    String topic = env("TOPIC_CONNECTION_STATE_CHANGED", "connection.state.changed");
    String jwksUrl = require("COGNITO_JWKS_URL");
    String internalApiKey = require("PRESENCE_INTERNAL_API_KEY");
    long ttlSeconds = Long.parseLong(env("CONNECTION_TTL_SECONDS", "7200"));
    int port = Integer.parseInt(env("PORT", "3000"));

    // --- DynamoDB. The endpoint override is the whole local-vs-AWS difference. ---
    var dynamoBuilder = DynamoDbClient.builder().region(software.amazon.awssdk.regions.Region.of(region));
    if (dynamoEndpoint != null && !dynamoEndpoint.isBlank()) {
      dynamoBuilder.endpointOverride(URI.create(dynamoEndpoint));
    }
    DynamoDbClient dynamo = dynamoBuilder.build();

    // --- Kafka producer. Plaintext locally (Redpanda); MSK IAM config is added
    //     here when deploying, not in the publisher. ---
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
    props.put(ProducerConfig.CLIENT_ID_CONFIG, "presence-connection");
    Producer<String, String> producer = new KafkaProducer<>(props);

    var repository = new DynamoConnectionRepository(dynamo, table);
    var publisher = new KafkaEventPublisher(producer, topic);
    var verifier = new JwksTokenVerifier(jwksUrl);
    var presence = new PresenceService(repository, publisher, Duration.ofSeconds(ttlSeconds));
    var router = new WebSocketRouter(presence, verifier);

    return new Config(presence, verifier, router, internalApiKey, port);
  }

  /** Test/alternate wiring: inject already-built collaborators. */
  public static Config forComponents(
      PresenceService presence,
      TokenVerifier verifier,
      WebSocketRouter router,
      String internalApiKey,
      int port) {
    return new Config(presence, verifier, router, internalApiKey, port);
  }
}
