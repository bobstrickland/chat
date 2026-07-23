package dev.rstrickland.chat.messaging.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rstrickland.chat.messaging.core.ConnectionLookup;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks up active connections via the Presence service's internal API
 * (GET /internal/presence/{userId}/connections, shared-key auth). Cross-service
 * API call — never a direct read of Presence's table.
 *
 * A lookup failure returns an empty list rather than throwing: a delivery to
 * one member must not be aborted because Presence was briefly unreachable.
 * The message is already persisted; the recipient will load it from history,
 * and Notification (Phase 5) covers the offline case.
 */
public final class PresenceConnectionLookup implements ConnectionLookup {

  // HTTP/1.1 for consistency with the ws-shim client and to avoid any h2c
  // upgrade negotiation against the plain HTTP backends.
  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(2))
          .build();
  private final ObjectMapper mapper = new ObjectMapper();
  private final String baseUrl;
  private final String internalApiKey;

  public PresenceConnectionLookup(String baseUrl, String internalApiKey) {
    if (baseUrl == null || baseUrl.isBlank() || internalApiKey == null || internalApiKey.isBlank()) {
      throw new IllegalArgumentException(
          "PRESENCE_SERVICE_URL and PRESENCE_INTERNAL_API_KEY are required");
    }
    this.baseUrl = baseUrl.replaceAll("/$", "");
    this.internalApiKey = internalApiKey;
  }

  @Override
  public List<String> activeConnections(String userId) {
    try {
      String url = baseUrl + "/internal/presence/" + encode(userId) + "/connections";
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(url))
              .header("x-internal-api-key", internalApiKey)
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        System.err.println("[messaging] presence lookup " + userId + " -> " + resp.statusCode());
        return List.of();
      }
      JsonNode node = mapper.readTree(resp.body());
      List<String> ids = new ArrayList<>();
      for (JsonNode c : node.path("connectionIds")) {
        ids.add(c.asText());
      }
      return ids;
    } catch (Exception e) {
      System.err.println("[messaging] presence lookup failed for " + userId + ": " + e.getMessage());
      return List.of();
    }
  }

  private static String encode(String s) {
    return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
  }
}
