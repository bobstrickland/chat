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
      if ("GET".equals(method) && path.startsWith("/conversations/direct/")) {
        String peerId = path.split("/")[3];
        List<Map<String, Object>> out = new ArrayList<>();
        for (Message m : config().messaging.directHistory(userId, peerId, 200)) {
          out.add(messageMap(m));
        }
        return reply(
            200,
            Map.of(
                "conversationId", MessagingService.directConversationId(userId, peerId),
                "messages", out));
      }
    } catch (IllegalArgumentException e) {
      return reply(400, Map.of("error", e.getMessage()));
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

  private static Map<String, Object> messageMap(Message m) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("conversationId", m.conversationId());
    map.put("messageId", m.messageId());
    map.put("senderId", m.senderId());
    map.put("body", m.body());
    map.put("sentAt", m.sentAt().toString());
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
