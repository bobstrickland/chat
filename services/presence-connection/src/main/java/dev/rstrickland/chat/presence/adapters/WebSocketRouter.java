package dev.rstrickland.chat.presence.adapters;

import dev.rstrickland.chat.presence.clients.TokenVerifier;
import dev.rstrickland.chat.presence.core.PresenceService;
import java.util.Map;

/**
 * Dispatches an API-Gateway-shaped WebSocket route event onto PresenceService.
 * Shared by both adapters (HttpServer locally/Fargate, Lambda in AWS) so the
 * $connect/$disconnect semantics are defined once.
 *
 * Returns an HTTP-ish status code: for $connect, a non-2xx tells ws-shim / API
 * Gateway to REJECT the handshake — that's how an unauthenticated socket is
 * refused before it ever opens.
 */
public final class WebSocketRouter {

  private final PresenceService presence;
  private final TokenVerifier verifier;

  public WebSocketRouter(PresenceService presence, TokenVerifier verifier) {
    this.presence = presence;
    this.verifier = verifier;
  }

  public int dispatch(String routeKey, String connectionId, Map<String, String> query) {
    if (routeKey == null || connectionId == null) {
      return 400;
    }
    switch (routeKey) {
      case "$connect":
        return handleConnect(connectionId, query);
      case "$disconnect":
        presence.disconnect(connectionId);
        return 200;
      default:
        // $default and any custom routes: presence doesn't handle message
        // payloads — that's Messaging (Phase 4). Accept and ignore.
        return 200;
    }
  }

  private int handleConnect(String connectionId, Map<String, String> query) {
    String token = query == null ? null : query.get("token");
    if (token == null || token.isBlank()) {
      System.err.println("[presence] $connect rejected: no token on handshake");
      return 401;
    }
    try {
      String userId = verifier.verifyAndGetUserId(token);
      String device = query.getOrDefault("device", "web");
      presence.connect(userId, connectionId, device);
      return 200;
    } catch (TokenVerifier.TokenVerificationException e) {
      System.err.println("[presence] $connect rejected: " + e.getMessage());
      return 401;
    }
  }
}
