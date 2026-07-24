package dev.rstrickland.chat.media.clients;

import dev.rstrickland.chat.media.core.MediaItem;
import dev.rstrickland.chat.media.core.MediaRepository;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/** media-metadata table (PK mediaId), low-level SDK v2. */
public final class DynamoMediaRepository implements MediaRepository {

  private final DynamoDbClient client;
  private final String table;

  public DynamoMediaRepository(DynamoDbClient client, String table) {
    if (table == null || table.isBlank()) {
      throw new IllegalArgumentException("MEDIA_METADATA_TABLE is not configured");
    }
    this.client = client;
    this.table = table;
  }

  private static AttributeValue s(String v) {
    return AttributeValue.builder().s(v).build();
  }

  @Override
  public void put(MediaItem m) {
    Map<String, AttributeValue> item = new HashMap<>();
    item.put("mediaId", s(m.mediaId()));
    item.put("ownerId", s(m.ownerId()));
    item.put("key", s(m.key()));
    if (m.thumbnailKey() != null) {
      item.put("thumbnailKey", s(m.thumbnailKey()));
    }
    item.put("contentType", s(m.contentType()));
    item.put("status", s(m.status()));
    item.put("size", AttributeValue.builder().n(Long.toString(m.size())).build());
    item.put("createdAt", s(m.createdAt()));
    client.putItem(PutItemRequest.builder().tableName(table).item(item).build());
  }

  @Override
  public MediaItem get(String mediaId) {
    var resp =
        client.getItem(
            GetItemRequest.builder().tableName(table).key(Map.of("mediaId", s(mediaId))).build());
    if (!resp.hasItem() || resp.item().isEmpty()) {
      return null;
    }
    Map<String, AttributeValue> i = resp.item();
    return new MediaItem(
        i.get("mediaId").s(),
        i.get("ownerId").s(),
        i.get("key").s(),
        i.containsKey("thumbnailKey") ? i.get("thumbnailKey").s() : null,
        i.get("contentType").s(),
        i.get("status").s(),
        i.containsKey("size") ? Long.parseLong(i.get("size").n()) : 0,
        i.get("createdAt").s());
  }
}
