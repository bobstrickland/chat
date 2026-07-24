package dev.rstrickland.chat.media.adapters;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rstrickland.chat.media.Config;
import dev.rstrickland.chat.media.clients.TokenVerifier;
import dev.rstrickland.chat.media.core.MediaService;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AWS-side adapters, same MediaService core as HttpServerMain:
 *   - `handleRequest` : API Gateway HTTP API (createUpload / requestProcessing / getMedia)
 *   - `workerHandler` : MSK-triggered consumer of media.uploaded (the transcode) —
 *                       the AWS equivalent of KafkaMediaConsumer, over MediaService.process
 * Not exercised locally (HttpServerMain + KafkaMediaConsumer are).
 */
public final class LambdaHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static volatile Config config;

  private static Config config() {
    if (config == null) {
      synchronized (LambdaHandler.class) {
        if (config == null) config = Config.fromEnv();
      }
    }
    return config;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
    Map<String, Object> ctx = (Map<String, Object>) event.getOrDefault("requestContext", Map.of());
    Map<String, Object> http = (Map<String, Object>) ctx.getOrDefault("http", Map.of());
    String method = String.valueOf(http.get("method"));
    String path = String.valueOf(http.get("path"));
    if ("/health".equals(path)) return reply(200, Map.of("status", "ok"));

    String userId;
    try {
      userId = config().verifier.verifyAndGetUserId(bearer(event));
    } catch (TokenVerifier.TokenVerificationException e) {
      return reply(401, Map.of("error", "invalid token"));
    }

    try {
      if ("POST".equals(method) && "/media/uploads".equals(path)) {
        JsonNode body = MAPPER.readTree(String.valueOf(event.getOrDefault("body", "{}")));
        MediaService.Upload up = config().media.createUpload(userId, body.path("contentType").asText(null));
        return reply(201, Map.of("mediaId", up.mediaId(), "uploadUrl", up.uploadUrl()));
      }
      var complete = java.util.regex.Pattern.compile("^/media/([^/]+)/complete$").matcher(path);
      if ("POST".equals(method) && complete.matches()) {
        return reply(202, viewMap(config().media.requestProcessing(userId, complete.group(1))));
      }
      var get = java.util.regex.Pattern.compile("^/media/([^/]+)$").matcher(path);
      if ("GET".equals(method) && get.matches()) {
        return reply(200, viewMap(config().media.getMedia(userId, get.group(1))));
      }
      return reply(404, Map.of("error", "not found"));
    } catch (IllegalArgumentException e) {
      return reply(400, Map.of("error", e.getMessage()));
    } catch (MediaService.ForbiddenException e) {
      return reply(403, Map.of("error", e.getMessage()));
    } catch (MediaService.NotFoundException e) {
      return reply(404, Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return reply(500, Map.of("error", "internal error"));
    }
  }

  /** MSK event source: each record is a media.uploaded event → run the transcode. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> workerHandler(Map<String, Object> event, Context context) {
    Object records = event.get("records");
    if (records instanceof Map<?, ?> byTopic) {
      for (Object list : ((Map<String, Object>) byTopic).values()) {
        if (list instanceof Iterable<?> recs) {
          for (Object rec : recs) {
            try {
              String value =
                  new String(
                      java.util.Base64.getDecoder()
                          .decode(String.valueOf(((Map<String, Object>) rec).get("value"))),
                      java.nio.charset.StandardCharsets.UTF_8);
              String mediaId = MAPPER.readTree(value).path("mediaId").asText(null);
              if (mediaId != null) {
                config().media.process(mediaId);
              }
            } catch (Exception e) {
              System.err.println("[media] worker record failed: " + e.getMessage());
            }
          }
        }
      }
    }
    return Map.of("ok", true);
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

  @SuppressWarnings("unchecked")
  private static String bearer(Map<String, Object> event) {
    Object headers = event.get("headers");
    if (headers instanceof Map<?, ?> m) {
      Object h = ((Map<String, Object>) m).get("authorization");
      if (h instanceof String s && s.startsWith("Bearer ")) return s.substring(7);
    }
    return "";
  }

  private static Map<String, Object> reply(int statusCode, Object body) {
    try {
      return Map.of("statusCode", statusCode, "body", MAPPER.writeValueAsString(body));
    } catch (Exception e) {
      return Map.of("statusCode", 500, "body", "{\"error\":\"serialization\"}");
    }
  }
}
