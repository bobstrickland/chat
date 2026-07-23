package dev.rstrickland.chat.messaging.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rstrickland.chat.messaging.core.Message;
import java.time.Instant;

/**
 * JSON (de)serialization for messages — kept in clients/ so core stays free of
 * Jackson. Two shapes:
 *   - the message.sent event on Kafka
 *   - the delivery frame pushed to a client's WebSocket ({type:"message", ...})
 * They share the same fields; the frame just adds a discriminating "type".
 */
public final class MessageJson {

  private final ObjectMapper mapper = new ObjectMapper();

  public String toEvent(Message m) {
    return fields(mapper.createObjectNode(), m).toString();
  }

  public String toFrame(Message m) {
    ObjectNode node = mapper.createObjectNode();
    node.put("type", "message");
    return fields(node, m).toString();
  }

  public Message fromEvent(String json) {
    try {
      JsonNode n = mapper.readTree(json);
      return new Message(
          n.get("conversationId").asText(),
          n.get("messageId").asText(),
          n.get("senderId").asText(),
          n.get("body").asText(),
          Instant.parse(n.get("sentAt").asText()));
    } catch (Exception e) {
      throw new RuntimeException("bad message.sent payload: " + json, e);
    }
  }

  private static ObjectNode fields(ObjectNode node, Message m) {
    node.put("conversationId", m.conversationId());
    node.put("messageId", m.messageId());
    node.put("senderId", m.senderId());
    node.put("body", m.body());
    node.put("sentAt", m.sentAt().toString());
    return node;
  }
}
