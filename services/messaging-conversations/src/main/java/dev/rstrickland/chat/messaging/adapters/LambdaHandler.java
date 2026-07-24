package dev.rstrickland.chat.messaging.adapters;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rstrickland.chat.messaging.Config;
import dev.rstrickland.chat.messaging.clients.TokenVerifier;
import dev.rstrickland.chat.messaging.core.Message;
import dev.rstrickland.chat.messaging.core.MessagingService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS-side adapter for the REST API (API Gateway HTTP API, proxy integration).
 * Calls the SAME MessagingService core as HttpServerMain.
 *
 * Delivery is NOT here: in AWS it runs as a separate MSK-triggered Lambda over
 * the same DeliveryService (message.sent is the trigger). That handler is a
 * Phase-10 deployment concern; locally HttpServerMain's consumer thread covers
 * it. Per CLAUDE.md, no reliance on warm context for correctness — the static
 * Config is a warm-start bonus only.
 */
public final class LambdaHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static volatile Config config;

  private static Config config() {
    Config c = config;
    if (c == null) {
      synchronized (LambdaHandler.class) {
        if (config == null) {
          config = Config.fromEnv();
        }
        c = config;
      }
    }
    return c;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
    Map<String, Object> ctx = (Map<String, Object>) event.getOrDefault("requestContext", Map.of());
    Map<String, Object> http = (Map<String, Object>) ctx.getOrDefault("http", Map.of());
    String method = String.valueOf(http.get("method"));
    String path = String.valueOf(http.get("path"));

    if ("/health".equals(path)) {
      return reply(200, Map.of("status", "ok"));
    }

    String userId;
    try {
      userId = config().verifier.verifyAndGetUserId(bearer(event));
    } catch (TokenVerifier.TokenVerificationException e) {
      return reply(401, Map.of("error", "invalid token"));
    }

    try {
      if ("POST".equals(method) && "/messages".equals(path)) {
        JsonNode body = MAPPER.readTree(String.valueOf(event.getOrDefault("body", "{}")));
        Message m =
            config().messaging.sendDirect(
                userId, textOf(body, "recipientId"), textOf(body, "body"));
        return reply(201, messageMap(m));
      }
      if ("GET".equals(method) && "/conversations".equals(path)) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var c : config().messaging.listConversations(userId)) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("conversationId", c.conversationId());
          row.put("type", c.type());
          row.put("name", c.name());
          row.put("peerId", c.peerId());
          row.put("lastMessage", c.lastMessage() == null ? null : messageMap(c.lastMessage()));
          out.add(row);
        }
        return reply(200, Map.of("conversations", out));
      }
      if ("POST".equals(method) && "/conversations".equals(path)) {
        JsonNode body = MAPPER.readTree(String.valueOf(event.getOrDefault("body", "{}")));
        List<String> memberIds = new ArrayList<>();
        JsonNode members = body.get("memberIds");
        if (members != null && members.isArray()) {
          members.forEach(m -> memberIds.add(m.asText()));
        }
        String conv = config().messaging.createGroup(userId, textOf(body, "name"), memberIds);
        return reply(201, Map.of("conversationId", conv, "type", "group", "name", textOf(body, "name")));
      }
      if ("GET".equals(method) && path.startsWith("/conversations/direct/")) {
        String peerId = path.split("/")[3];
        return reply(200, historyBody(userId, MessagingService.directConversationId(userId, peerId),
            config().messaging.directHistory(userId, peerId, 200)));
      }
      // /conversations/{conversationId}/messages
      java.util.regex.Matcher mm =
          java.util.regex.Pattern.compile("^/conversations/([^/]+)/messages$").matcher(path);
      if (mm.matches()) {
        String conversationId = java.net.URLDecoder.decode(mm.group(1), java.nio.charset.StandardCharsets.UTF_8);
        if ("GET".equals(method)) {
          return reply(200, historyBody(userId, conversationId,
              config().messaging.conversationHistory(userId, conversationId, 200)));
        }
        if ("POST".equals(method)) {
          JsonNode body = MAPPER.readTree(String.valueOf(event.getOrDefault("body", "{}")));
          Message m = config().messaging.sendToConversation(
              userId, conversationId, textOf(body, "body"), textOf(body, "mediaId"));
          return reply(201, messageMap(m));
        }
      }
      // POST /conversations/{conversationId}/receipts
      java.util.regex.Matcher rm =
          java.util.regex.Pattern.compile("^/conversations/([^/]+)/receipts$").matcher(path);
      if (rm.matches() && "POST".equals(method)) {
        String conversationId = java.net.URLDecoder.decode(rm.group(1), java.nio.charset.StandardCharsets.UTF_8);
        JsonNode body = MAPPER.readTree(String.valueOf(event.getOrDefault("body", "{}")));
        List<String> members =
            config().messaging.recordReceipt(userId, conversationId, textOf(body, "kind"), textOf(body, "position"));
        config().receiptBroadcaster.broadcast(
            conversationId, userId, textOf(body, "kind"), textOf(body, "position"), members);
        return reply(200, Map.of("ok", true));
      }
    } catch (IllegalArgumentException e) {
      return reply(400, Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return reply(403, Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return reply(500, Map.of("error", "internal error"));
    }
    return reply(404, Map.of("error", "not found"));
  }

  @SuppressWarnings("unchecked")
  private static String bearer(Map<String, Object> event) {
    Object headers = event.get("headers");
    if (headers instanceof Map<?, ?> m) {
      Object h = ((Map<String, Object>) m).get("authorization");
      if (h instanceof String s && s.startsWith("Bearer ")) {
        return s.substring(7);
      }
    }
    return "";
  }

  private static String textOf(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static Map<String, Object> historyBody(
      String userId, String conversationId, List<Message> messages) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Message m : messages) {
      out.add(messageMap(m));
    }
    List<Map<String, Object>> receipts = new ArrayList<>();
    for (var r : config().messaging.conversationReceipts(userId, conversationId)) {
      receipts.add(Map.of("userId", r.userId(), "kind", r.kind(), "position", r.position()));
    }
    return Map.of("conversationId", conversationId, "messages", out, "receipts", receipts);
  }

  private static Map<String, Object> messageMap(Message m) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("conversationId", m.conversationId());
    map.put("messageId", m.messageId());
    map.put("senderId", m.senderId());
    map.put("body", m.body());
    map.put("sentAt", m.sentAt().toString());
    map.put("mediaId", m.mediaId());
    return map;
  }

  private static Map<String, Object> reply(int status, Object body) {
    try {
      return Map.of("statusCode", status, "body", MAPPER.writeValueAsString(body));
    } catch (Exception e) {
      return Map.of("statusCode", 500, "body", "{\"error\":\"serialization\"}");
    }
  }
}
