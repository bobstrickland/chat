package dev.rstrickland.chat.messaging.clients;

import dev.rstrickland.chat.messaging.core.ConversationRepository;
import dev.rstrickland.chat.messaging.core.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Single-table Conversations repository (AWS SDK v2 low-level).
 *
 * Item shapes (PK conversationId, SK sk):
 *   meta            sk = "meta"                       {type, createdAt}
 *   member          sk = "member#{userId}"            {userId}  ← userId feeds gsi-user-conversations
 *   message         sk = "ts#{sentAt}#{messageId}"    {messageId, senderId, body, sentAt}
 *
 * Messages sort chronologically because the ISO-8601 instant in the SK sorts
 * lexicographically in the same order as time.
 */
public final class DynamoConversationRepository implements ConversationRepository {

  private static final String MSG_PREFIX = "ts#";
  private static final String MEMBER_PREFIX = "member#";

  private final DynamoDbClient client;
  private final String table;

  public DynamoConversationRepository(DynamoDbClient client, String table) {
    if (table == null || table.isBlank()) {
      throw new IllegalArgumentException("CONVERSATIONS_TABLE is not configured");
    }
    this.client = client;
    this.table = table;
  }

  private static AttributeValue s(String v) {
    return AttributeValue.builder().s(v).build();
  }

  @Override
  public void ensureDirectConversation(String conversationId, String userA, String userB) {
    // Create meta once (conditional). If it already exists, the members were
    // created alongside it on that first call, so we can stop.
    try {
      client.putItem(
          PutItemRequest.builder()
              .tableName(table)
              .item(
                  Map.of(
                      "conversationId", s(conversationId),
                      "sk", s("meta"),
                      "type", s("direct"),
                      "createdAt", s(Instant.now().toString())))
              .conditionExpression("attribute_not_exists(sk)")
              .build());
    } catch (ConditionalCheckFailedException alreadyExists) {
      return;
    }
    putMember(conversationId, userA);
    putMember(conversationId, userB);
  }

  private void putMember(String conversationId, String userId) {
    client.putItem(
        PutItemRequest.builder()
            .tableName(table)
            .item(
                Map.of(
                    "conversationId", s(conversationId),
                    "sk", s(MEMBER_PREFIX + userId),
                    "userId", s(userId),
                    "joinedAt", s(Instant.now().toString())))
            .build());
  }

  @Override
  public void saveMessage(Message m) {
    client.putItem(
        PutItemRequest.builder()
            .tableName(table)
            .item(
                Map.of(
                    "conversationId", s(m.conversationId()),
                    "sk", s(MSG_PREFIX + m.sentAt().toString() + "#" + m.messageId()),
                    "messageId", s(m.messageId()),
                    "senderId", s(m.senderId()),
                    "body", s(m.body()),
                    "sentAt", s(m.sentAt().toString())))
            .build());
  }

  @Override
  public List<Message> listMessages(String conversationId, int limit) {
    QueryResponse resp =
        client.query(
            QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("conversationId = :c AND begins_with(sk, :p)")
                .expressionAttributeValues(Map.of(":c", s(conversationId), ":p", s(MSG_PREFIX)))
                .scanIndexForward(true) // chronological (oldest first)
                .limit(limit)
                .build());
    List<Message> messages = new ArrayList<>(resp.items().size());
    for (Map<String, AttributeValue> item : resp.items()) {
      messages.add(
          new Message(
              conversationId,
              item.get("messageId").s(),
              item.get("senderId").s(),
              item.get("body").s(),
              Instant.parse(item.get("sentAt").s())));
    }
    return messages;
  }

  private static final String GSI_USER = "gsi-user-conversations";

  @Override
  public List<String> userConversations(String userId) {
    QueryResponse resp =
        client.query(
            QueryRequest.builder()
                .tableName(table)
                .indexName(GSI_USER)
                .keyConditionExpression("userId = :u")
                .expressionAttributeValues(Map.of(":u", s(userId)))
                .build());
    List<String> ids = new ArrayList<>(resp.items().size());
    for (Map<String, AttributeValue> item : resp.items()) {
      AttributeValue v = item.get("conversationId");
      if (v != null) {
        ids.add(v.s());
      }
    }
    return ids;
  }

  @Override
  public Message lastMessage(String conversationId) {
    QueryResponse resp =
        client.query(
            QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("conversationId = :c AND begins_with(sk, :p)")
                .expressionAttributeValues(Map.of(":c", s(conversationId), ":p", s(MSG_PREFIX)))
                .scanIndexForward(false) // newest first
                .limit(1)
                .build());
    if (resp.items().isEmpty()) {
      return null;
    }
    Map<String, AttributeValue> item = resp.items().get(0);
    return new Message(
        conversationId,
        item.get("messageId").s(),
        item.get("senderId").s(),
        item.get("body").s(),
        Instant.parse(item.get("sentAt").s()));
  }

  @Override
  public List<String> members(String conversationId) {
    QueryResponse resp =
        client.query(
            QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("conversationId = :c AND begins_with(sk, :p)")
                .expressionAttributeValues(Map.of(":c", s(conversationId), ":p", s(MEMBER_PREFIX)))
                .build());
    List<String> ids = new ArrayList<>(resp.items().size());
    for (Map<String, AttributeValue> item : resp.items()) {
      AttributeValue v = item.get("userId");
      if (v != null) {
        ids.add(v.s());
      }
    }
    return ids;
  }
}
