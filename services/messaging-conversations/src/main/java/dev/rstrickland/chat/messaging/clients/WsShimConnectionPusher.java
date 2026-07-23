package dev.rstrickland.chat.messaging.clients;

import dev.rstrickland.chat.messaging.core.ConnectionPusher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Pushes to a WebSocket connection via ws-shim's postToConnection
 * (POST {endpoint}{managePath}/{connectionId}) — the local stand-in for
 * ApiGatewayManagementApi.postToConnection. In AWS this becomes an
 * ApiGatewayManagementApiClient call against the WS API's @connections endpoint;
 * only this client changes, not core.
 *
 * A 410 means the connection is gone (client vanished without a clean
 * $disconnect). That's expected — return false so the caller skips it — not an
 * error.
 */
public final class WsShimConnectionPusher implements ConnectionPusher {

  // Force HTTP/1.1: the JDK client defaults to HTTP/2 and sends an h2c upgrade
  // header, which ws-shim's Node http server misreads as a WebSocket upgrade —
  // routing the POST into the $connect handler and rejecting it. Real API
  // Gateway wouldn't do this; it's a ws-shim fidelity quirk, pinned around here.
  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(2))
          .build();
  private final String base; // e.g. http://ws-shim:8090/@connections

  public WsShimConnectionPusher(String endpoint, String managePath) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("WS_SHIM_ENDPOINT is required");
    }
    String path = (managePath == null || managePath.isBlank()) ? "/@connections" : managePath;
    this.base = endpoint.replaceAll("/$", "") + path;
  }

  @Override
  public boolean push(String connectionId, String jsonPayload) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(base + "/" + encode(connectionId)))
              .header("content-type", "application/json")
              .timeout(Duration.ofSeconds(3))
              .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
              .build();
      HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
      if (resp.statusCode() == 410) {
        return false; // GoneException — stale connection
      }
      if (resp.statusCode() >= 300) {
        System.err.println("[messaging] push " + connectionId + " -> " + resp.statusCode());
        return false;
      }
      return true;
    } catch (Exception e) {
      System.err.println("[messaging] push failed for " + connectionId + ": " + e.getMessage());
      return false;
    }
  }

  private static String encode(String s) {
    return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
  }
}
