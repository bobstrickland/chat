package dev.rstrickland.chat.messaging.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rstrickland.chat.messaging.Config;
import dev.rstrickland.chat.messaging.clients.TokenVerifier;
import dev.rstrickland.chat.messaging.core.Message;
import dev.rstrickland.chat.messaging.core.MessagingService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Local/Fargate adapter. Serves the REST API AND starts the delivery consumer
 * (in AWS these split: an HTTP-API Lambda for the routes, an MSK-triggered
 * Lambda for delivery). Thin — parse, call core, serialize.
 *
 * Routes (all bearer-authed except /health):
 *   POST /messages                                  { recipientId, body } -> message
 *   GET  /conversations/direct/{peerId}/messages    -> { conversationId, messages }
 *   GET  /health
 */
public final class HttpServerMain {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Config config;

  public HttpServerMain(Config config) {
    this.config = config;
  }

  public static void main(String[] args) throws IOException {
    Config config = Config.fromEnv();
    // Start real-time delivery before serving, so a message sent the instant we
    // come up still gets delivered.
    config.deliveryConsumer.start();
    new HttpServerMain(config).start();
  }

  public void start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(config.port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/health", this::handleHealth);
    server.createContext("/messages", this::handleSend);
    // Longest-prefix match: "/conversations" (exact) -> list; "/conversations/..." -> history.
    server.createContext("/conversations", this::handleList);
    server.createContext("/conversations/", this::handleHistory);
    server.start();
    System.out.println("messaging-conversations (HttpServer adapter) listening on :" + config.port);
  }

  private void handleHealth(HttpExchange ex) throws IOException {
    respond(ex, 200, Map.of("status", "ok"));
  }

  private void handleSend(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) {
      respond(ex, 405, Map.of("error", "method not allowed"));
      return;
    }
    String senderId = authenticate(ex);
    if (senderId == null) {
      return;
    }
    JsonNode body = MAPPER.readTree(ex.getRequestBody());
    String recipientId = text(body, "recipientId");
    String messageBody = text(body, "body");
    try {
      Message m = config.messaging.sendDirect(senderId, recipientId, messageBody);
      respond(ex, 201, messageMap(m));
    } catch (IllegalArgumentException e) {
      respond(ex, 400, Map.of("error", e.getMessage()));
    }
  }

  private void handleList(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) {
      respond(ex, 405, Map.of("error", "method not allowed"));
      return;
    }
    String userId = authenticate(ex);
    if (userId == null) {
      return;
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (var c : config.messaging.listConversations(userId)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("conversationId", c.conversationId());
      row.put("peerId", c.peerId());
      row.put("lastMessage", c.lastMessage() == null ? null : messageMap(c.lastMessage()));
      out.add(row);
    }
    respond(ex, 200, Map.of("conversations", out));
  }

  private void handleHistory(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) {
      respond(ex, 405, Map.of("error", "method not allowed"));
      return;
    }
    String userId = authenticate(ex);
    if (userId == null) {
      return;
    }
    // /conversations/direct/{peerId}/messages
    String path = ex.getRequestURI().getPath();
    String rest = path.substring("/conversations/".length());
    String[] parts = rest.split("/");
    if (parts.length != 3 || !parts[0].equals("direct") || !parts[2].equals("messages")) {
      respond(ex, 404, Map.of("error", "not found"));
      return;
    }
    String peerId = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
    List<Message> messages = config.messaging.directHistory(userId, peerId, 200);

    List<Map<String, Object>> out = new ArrayList<>(messages.size());
    for (Message m : messages) {
      out.add(messageMap(m));
    }
    respond(
        ex,
        200,
        Map.of(
            "conversationId", MessagingService.directConversationId(userId, peerId),
            "messages", out));
  }

  // --- helpers --------------------------------------------------------------

  /** Returns the caller's userId, or null after having already written a 401. */
  private String authenticate(HttpExchange ex) throws IOException {
    String header = ex.getRequestHeaders().getFirst("Authorization");
    String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
    if (token == null) {
      respond(ex, 401, Map.of("error", "missing bearer token"));
      return null;
    }
    try {
      return config.verifier.verifyAndGetUserId(token);
    } catch (TokenVerifier.TokenVerificationException e) {
      respond(ex, 401, Map.of("error", "invalid token"));
      return null;
    }
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

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static void respond(HttpExchange ex, int status, Object body) throws IOException {
    byte[] bytes = MAPPER.writeValueAsBytes(body);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }
}
