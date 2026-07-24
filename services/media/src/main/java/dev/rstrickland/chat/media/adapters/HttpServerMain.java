package dev.rstrickland.chat.media.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rstrickland.chat.media.Config;
import dev.rstrickland.chat.media.clients.TokenVerifier;
import dev.rstrickland.chat.media.core.MediaService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Local/Fargate adapter. Routes:
 *   POST /media/uploads              { contentType } -> { mediaId, uploadUrl }
 *   POST /media/{mediaId}/complete   -> media view (runs the thumbnail transform)
 *   GET  /media/{mediaId}            -> media view (presigned URLs)
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
    config.worker.start(); // the media.uploaded processing worker
    new HttpServerMain(config).start();
  }

  public void start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(config.port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/health", ex -> respond(ex, 200, Map.of("status", "ok")));
    server.createContext("/media", this::handleMedia);
    server.start();
    System.out.println("media (HttpServer adapter) listening on :" + config.port);
  }

  private void handleMedia(HttpExchange ex) throws IOException {
    String userId = authenticate(ex);
    if (userId == null) {
      return;
    }
    String method = ex.getRequestMethod();
    String path = ex.getRequestURI().getPath(); // /media/...

    try {
      if ("POST".equals(method) && path.equals("/media/uploads")) {
        JsonNode body = MAPPER.readTree(ex.getRequestBody());
        MediaService.Upload up = config.media.createUpload(userId, text(body, "contentType"));
        respond(ex, 201, Map.of("mediaId", up.mediaId(), "uploadUrl", up.uploadUrl()));
        return;
      }
      java.util.regex.Matcher complete =
          java.util.regex.Pattern.compile("^/media/([^/]+)/complete$").matcher(path);
      if ("POST".equals(method) && complete.matches()) {
        // Enqueue only — the worker does the transcode. Returns "processing".
        respond(ex, 202, viewMap(config.media.requestProcessing(userId, complete.group(1))));
        return;
      }
      java.util.regex.Matcher get =
          java.util.regex.Pattern.compile("^/media/([^/]+)$").matcher(path);
      if ("GET".equals(method) && get.matches()) {
        respond(ex, 200, viewMap(config.media.getMedia(userId, get.group(1))));
        return;
      }
      respond(ex, 404, Map.of("error", "not found"));
    } catch (IllegalArgumentException e) {
      respond(ex, 400, Map.of("error", e.getMessage()));
    } catch (MediaService.ForbiddenException e) {
      respond(ex, 403, Map.of("error", e.getMessage()));
    } catch (MediaService.NotFoundException e) {
      respond(ex, 404, Map.of("error", e.getMessage()));
    }
  }

  private static Map<String, Object> viewMap(MediaService.MediaView v) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("mediaId", v.mediaId());
    m.put("contentType", v.contentType());
    m.put("status", v.status());
    m.put("url", v.url());
    m.put("thumbnailUrl", v.thumbnailUrl());
    return m;
  }

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
