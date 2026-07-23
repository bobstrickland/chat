package dev.rstrickland.chat.messaging.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the receipt frame pushed over the WebSocket:
 * { type:"receipt", conversationId, userId, kind, position }.
 * Kept in clients/ so ReceiptBroadcaster (core) stays free of Jackson.
 */
public final class ReceiptJson {
  private final ObjectMapper mapper = new ObjectMapper();

  public String toFrame(String conversationId, String userId, String kind, String position) {
    ObjectNode node = mapper.createObjectNode();
    node.put("type", "receipt");
    node.put("conversationId", conversationId);
    node.put("userId", userId);
    node.put("kind", kind);
    node.put("position", position);
    return node.toString();
  }
}
