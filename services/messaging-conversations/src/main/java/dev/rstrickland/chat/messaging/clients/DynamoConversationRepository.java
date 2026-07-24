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

  @Override
  public void createGroup(
      String conversationId, String name, String createdBy, java.util.List<String> members) {
    client.putItem(
        PutItemRequest.builder()
            .tableName(table)
            .item(
                Map.of(
                    "conversationId", s(conversationId),
                    "sk", s("meta"),
                    "type", s("group"),
                    "name", s(name),
                    "createdBy", s(createdBy),
                    "createdAt", s(Instant.now().toString())))
            .build());
    for (String member : members) {
      putMember(conversationId, member);
    }
  }

  @Override
  public dev.rstrickland.chat.messaging.core.ConversationMeta meta(String conversationId) {
    var resp =
        client.getItem(
            software.amazon.awssdk.services.dynamodb.model.GetItemRequest.builder()
                .tableName(table)
                .key(Map.of("conversationId", s(conversationId), "sk", s("meta")))
                .build());
    if (!resp.hasItem() || resp.item().isEmpty()) {
      return null;
    }
    Map<String, AttributeValue> item = resp.item();
    AttributeValue type = item.get("type");
    AttributeValue name = item.get("name");
    return new dev.rstrickland.chat.messaging.core.ConversationMeta(
        type == null ? null : type.s(), name == null ? null : name.s());
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
    java.util.Map<String, AttributeValue> item = new java.util.HashMap<>();
    item.put("conversationId", s(m.conversationId()));
    item.put("sk", s(MSG_PREFIX + m.sentAt().toString() + "#" + m.messageId()));
    item.put("messageId", s(m.messageId()));
    item.put("senderId", s(m.senderId()));
    item.put("body", s(m.body()));
    item.put("sentAt", s(m.sentAt().toString()));
    if (m.mediaId() != null) {
      item.put("mediaId", s(m.mediaId()));
    }
    client.putItem(PutItemRequest.builder().tableName(table).item(item).build());
  }

  private static Message toMessage(String conversationId, Map<String, AttributeValue> item) {
    AttributeValue media = item.get("mediaId");
    return new Message(
        conversationId,
        item.get("messageId").s(),
        item.get("senderId").s(),
        item.get("body").s(),
        Instant.parse(item.get("sentAt").s()),
        media == null ? null : media.s());
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
      messages.add(toMessage(conversationId, item));
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
    // Dedupe defensively: any past item that carried a userId (e.g. receipts
    // written before that attribute was removed) can still produce duplicate
    // GSI rows for the same conversation. A LinkedHashSet collapses them while
    // keeping order.
    java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
    for (Map<String, AttributeValue> item : resp.items()) {
      AttributeValue v = item.get("conversationId");
      if (v != null) {
        ids.add(v.s());
      }
    }
    return new ArrayList<>(ids);
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
    return toMessage(conversationId, resp.items().get(0));
  }

  private static final String RECEIPT_PREFIX = "rcpt#";

  @Override
  public void upsertReceipt(String conversationId, String kind, String userId, String position) {
    // SK "rcpt#{kind}#{userId}" — a distinct namespace from member#/ts#/meta.
    // IMPORTANT: no `userId` attribute here. gsi-user-conversations indexes any
    // item that HAS a userId, so a userId on a receipt item would make the
    // conversation appear once per receipt in userConversations() (duplicate
    // rows in the list). The userId is recoverable from the SK.
    // Conditional so a receipt only ever moves FORWARD.
    try {
      client.putItem(
          PutItemRequest.builder()
              .tableName(table)
              .item(
                  Map.of(
                      "conversationId", s(conversationId),
                      "sk", s(RECEIPT_PREFIX + kind + "#" + userId),
                      "kind", s(kind),
                      "position", s(position)))
              .conditionExpression("attribute_not_exists(sk) OR #p < :p")
              .expressionAttributeNames(Map.of("#p", "position"))
              .expressionAttributeValues(Map.of(":p", s(position)))
              .build());
    } catch (ConditionalCheckFailedException older) {
      // Existing position is newer/equal — nothing to do.
    }
  }

  @Override
  public List<dev.rstrickland.chat.messaging.core.Receipt> receipts(String conversationId) {
    QueryResponse resp =
        client.query(
            QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("conversationId = :c AND begins_with(sk, :p)")
                .expressionAttributeValues(Map.of(":c", s(conversationId), ":p", s(RECEIPT_PREFIX)))
                .build());
    List<dev.rstrickland.chat.messaging.core.Receipt> out = new ArrayList<>(resp.items().size());
    for (Map<String, AttributeValue> item : resp.items()) {
      // userId is parsed from the SK "rcpt#{kind}#{userId}" (no userId attribute
      // — see upsertReceipt). A userId can contain no '#', so this is unambiguous.
      String sk = item.get("sk").s();
      String userId = sk.substring(sk.indexOf('#', RECEIPT_PREFIX.length()) + 1);
      out.add(
          new dev.rstrickland.chat.messaging.core.Receipt(
              userId, item.get("kind").s(), item.get("position").s()));
    }
    return out;
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
