package dev.rstrickland.chat.messaging;

import dev.rstrickland.chat.messaging.clients.DynamoConversationRepository;
import dev.rstrickland.chat.messaging.clients.JwksTokenVerifier;
import dev.rstrickland.chat.messaging.clients.KafkaDeliveryConsumer;
import dev.rstrickland.chat.messaging.clients.KafkaMessagePublisher;
import dev.rstrickland.chat.messaging.clients.MessageJson;
import dev.rstrickland.chat.messaging.clients.PresenceConnectionLookup;
import dev.rstrickland.chat.messaging.clients.TokenVerifier;
import dev.rstrickland.chat.messaging.clients.WsShimConnectionPusher;
import dev.rstrickland.chat.messaging.core.DeliveryService;
import dev.rstrickland.chat.messaging.core.MessagingService;
import java.net.URI;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Single wiring point (the config.js analogue). Plain constructor DI, no Spring. */
public final class Config {

  public final MessagingService messaging;
  public final TokenVerifier verifier;
  public final KafkaDeliveryConsumer deliveryConsumer;
  public final int port;

  private Config(
      MessagingService messaging,
      TokenVerifier verifier,
      KafkaDeliveryConsumer deliveryConsumer,
      int port) {
    this.messaging = messaging;
    this.verifier = verifier;
    this.deliveryConsumer = deliveryConsumer;
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
    String table = env("CONVERSATIONS_TABLE", "conversations-local");
    String dynamoEndpoint = System.getenv("DYNAMODB_ENDPOINT");
    String brokers = require("KAFKA_BROKERS");
    String topic = env("TOPIC_MESSAGE_SENT", "message.sent");
    String jwksUrl = require("COGNITO_JWKS_URL");
    String presenceUrl = require("PRESENCE_SERVICE_URL");
    String presenceKey = require("PRESENCE_INTERNAL_API_KEY");
    String wsEndpoint = require("WS_SHIM_ENDPOINT");
    String wsManagePath = env("WS_SHIM_MANAGE_CONNECTIONS_PATH", "/@connections");
    String deliveryGroup = env("MESSAGING_DELIVERY_GROUP", "messaging-delivery");
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
    props.put(ProducerConfig.CLIENT_ID_CONFIG, "messaging-conversations");
    Producer<String, String> producer = new KafkaProducer<>(props);

    MessageJson json = new MessageJson();
    var repository = new DynamoConversationRepository(dynamo, table);
    var publisher = new KafkaMessagePublisher(producer, topic);
    var verifier = new JwksTokenVerifier(jwksUrl);
    var lookup = new PresenceConnectionLookup(presenceUrl, presenceKey);
    var pusher = new WsShimConnectionPusher(wsEndpoint, wsManagePath);

    var messaging = new MessagingService(repository, publisher);
    var delivery = new DeliveryService(repository, lookup, pusher, json::toFrame);
    var deliveryConsumer = new KafkaDeliveryConsumer(brokers, topic, deliveryGroup, delivery);

    return new Config(messaging, verifier, deliveryConsumer, port);
  }
}
