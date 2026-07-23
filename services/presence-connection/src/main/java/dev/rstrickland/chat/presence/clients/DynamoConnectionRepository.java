package dev.rstrickland.chat.presence.clients;

import dev.rstrickland.chat.presence.core.Connection;
import dev.rstrickland.chat.presence.core.ConnectionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * DynamoDB-backed ConnectionRepository (AWS SDK v2, low-level client — no
 * enhanced-client dependency needed for this handful of attributes).
 *
 * Table `presence-connections`: PK userId, SK connectionId, TTL on expiresAt,
 * plus GSI `gsi-connection` (PK connectionId, KEYS_ONLY) for the
 * connectionId→userId lookup that $disconnect needs.
 */
public final class DynamoConnectionRepository implements ConnectionRepository {

  private static final String GSI = "gsi-connection";

  private final DynamoDbClient client;
  private final String table;

  public DynamoConnectionRepository(DynamoDbClient client, String table) {
    if (table == null || table.isBlank()) {
      throw new IllegalArgumentException("PRESENCE_CONNECTIONS_TABLE is not configured");
    }
    this.client = client;
    this.table = table;
  }

  private static AttributeValue s(String v) {
    return AttributeValue.builder().s(v).build();
  }

  private static AttributeValue n(long v) {
    return AttributeValue.builder().n(Long.toString(v)).build();
  }

  @Override
  public void save(Connection c) {
    Map<String, AttributeValue> item =
        Map.of(
            "userId", s(c.userId()),
            "connectionId", s(c.connectionId()),
            "device", s(c.device()),
            "connectedAt", s(c.connectedAt().toString()),
            "expiresAt", n(c.expiresAt()));
    client.putItem(PutItemRequest.builder().tableName(table).item(item).build());
  }

  @Override
  public Optional<String> findUserByConnection(String connectionId) {
    QueryResponse resp =
        client.query(
            QueryRequest.builder()
                .tableName(table)
                .indexName(GSI)
                .keyConditionExpression("connectionId = :c")
                .expressionAttributeValues(Map.of(":c", s(connectionId)))
                .limit(1)
                .build());
    if (resp.items().isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(resp.items().get(0).get("userId")).map(AttributeValue::s);
  }

  @Override
  public void delete(String userId, String connectionId) {
    client.deleteItem(
        DeleteItemRequest.builder()
            .tableName(table)
            .key(Map.of("userId", s(userId), "connectionId", s(connectionId)))
            .build());
  }

  @Override
  public List<String> activeConnectionIds(String userId) {
    QueryResponse resp =
        client.query(
            QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("userId = :u")
                .expressionAttributeValues(Map.of(":u", s(userId)))
                .build());
    List<String> ids = new ArrayList<>(resp.items().size());
    for (Map<String, AttributeValue> item : resp.items()) {
      AttributeValue v = item.get("connectionId");
      if (v != null) {
        ids.add(v.s());
      }
    }
    return ids;
  }
}
