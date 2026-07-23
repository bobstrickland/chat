package dev.rstrickland.chat.presence.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rstrickland.chat.presence.Config;
import dev.rstrickland.chat.presence.clients.TokenVerifier;
import dev.rstrickland.chat.presence.core.PresenceService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Local/Fargate adapter — plain JDK HttpServer, no framework. This is what runs
 * under docker-compose today; the Lambda adapter is the AWS-side equivalent.
 * Both are thin: parse the transport, call core, serialize the result.
 *
 * Routes:
 *   POST /ws                                  — ws-shim / API GW route events
 *   GET  /presence/status/{userId}            — bearer; { userId, online }
 *   GET  /internal/presence/{userId}/connections — internal key; getActiveConnections
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
    new HttpServerMain(config).start();
  }

  public void start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(config.port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

    server.createContext("/health", this::handleHealth);
    server.createContext("/ws", this::handleWs);
    server.createContext("/presence/status/", this::handleStatus);
    server.createContext("/internal/presence/", this::handleInternalConnections);

    server.start();
    System.out.println("presence-connection (HttpServer adapter) listening on :" + config.port);
  }

  // --- routes ---------------------------------------------------------------

  private void handleHealth(HttpExchange ex) throws IOException {
    respond(ex, 200, Map.of("status", "ok"));
  }

  /** ws-shim posts the API-GW-shaped route event here. */
  private void handleWs(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) {
      respond(ex, 405, Map.of("error", "method not allowed"));
      return;
    }
    JsonNode event = MAPPER.readTree(ex.getRequestBody());
    JsonNode ctx = event.path("requestContext");
    String routeKey = ctx.path("routeKey").asText(null);
    String connectionId = ctx.path("connectionId").asText(null);

    Map<String, String> query = new HashMap<>();
    JsonNode qsp = event.path("queryStringParameters");
    if (qsp.isObject()) {
      qsp.fields().forEachRemaining(e -> query.put(e.getKey(), e.getValue().asText()));
    }

    int status = config.webSocketRouter.dispatch(routeKey, connectionId, query);
    // Echo the event back on success (some integrations expect it); ws-shim only
    // cares about the status code.
    respond(ex, status, Map.of("statusCode", status));
  }

  /** GET /presence/status/{userId} — any authenticated user may query presence. */
  private void handleStatus(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) {
      respond(ex, 405, Map.of("error", "method not allowed"));
      return;
    }
    if (!requireBearer(ex)) {
      return;
    }
    String userId = tail(ex.getRequestURI().getPath(), "/presence/status/");
    if (userId.isBlank()) {
      respond(ex, 404, Map.of("error", "userId required"));
      return;
    }
    PresenceService presence = config.presence;
    respond(ex, 200, Map.of("userId", userId, "online", presence.isOnline(userId)));
  }

  /**
   * GET /internal/presence/{userId}/connections — service-to-service
   * (Messaging's delivery path). Protected by the shared internal key, matching
   * the Profile service's internal-route pattern.
   */
  private void handleInternalConnections(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) {
      respond(ex, 405, Map.of("error", "method not allowed"));
      return;
    }
    if (!requireInternalKey(ex)) {
      return;
    }
    String path = ex.getRequestURI().getPath(); // /internal/presence/{userId}/connections
    String rest = tail(path, "/internal/presence/");
    if (!rest.endsWith("/connections")) {
      respond(ex, 404, Map.of("error", "not found"));
      return;
    }
    String userId = rest.substring(0, rest.length() - "/connections".length());
    if (userId.isBlank()) {
      respond(ex, 404, Map.of("error", "userId required"));
      return;
    }
    List<String> ids = config.presence.getActiveConnections(userId);
    respond(ex, 200, Map.of("userId", userId, "connectionIds", ids, "online", !ids.isEmpty()));
  }

  // --- helpers --------------------------------------------------------------

  private boolean requireBearer(HttpExchange ex) throws IOException {
    String header = ex.getRequestHeaders().getFirst("Authorization");
    String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
    if (token == null) {
      respond(ex, 401, Map.of("error", "missing bearer token"));
      return false;
    }
    try {
      config.verifier.verifyAndGetUserId(token);
      return true;
    } catch (TokenVerifier.TokenVerificationException e) {
      respond(ex, 401, Map.of("error", "invalid token"));
      return false;
    }
  }

  private boolean requireInternalKey(HttpExchange ex) throws IOException {
    String provided = ex.getRequestHeaders().getFirst("x-internal-api-key");
    if (!constantTimeEquals(provided, config.internalApiKey)) {
      respond(ex, 401, Map.of("error", "invalid internal api key"));
      return false;
    }
    return true;
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    byte[] x = a.getBytes(StandardCharsets.UTF_8);
    byte[] y = b.getBytes(StandardCharsets.UTF_8);
    return java.security.MessageDigest.isEqual(x, y);
  }

  private static String tail(String path, String prefix) {
    return path.startsWith(prefix) ? path.substring(prefix.length()) : "";
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
