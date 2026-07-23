package dev.rstrickland.chat.presence.core;

import java.time.Instant;

/**
 * One active WebSocket connection. A user may have several at once (web + mobile
 * coexist under the same userId — CLAUDE.md Data Model Notes), which is why the
 * table is keyed (userId, connectionId).
 *
 * @param userId       Cognito subject (from the verified token)
 * @param connectionId API Gateway / ws-shim assigned id
 * @param device       "web" | "ios" | "android" — informational, from the handshake
 * @param connectedAt  when the connection opened
 * @param expiresAt    TTL epoch-seconds; a safety net so a missed $disconnect
 *                     still reaps the row rather than leaving a ghost presence
 */
public record Connection(
    String userId,
    String connectionId,
    String device,
    Instant connectedAt,
    long expiresAt) {}
