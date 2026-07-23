package dev.rstrickland.chat.messaging.core;

import java.util.List;

/**
 * Looks up a user's active WebSocket connections. Implemented against the
 * Presence & Connection service's internal API — cross-service data via API
 * call, never a direct read of Presence's table (CLAUDE.md No shared databases).
 */
public interface ConnectionLookup {
  List<String> activeConnections(String userId);
}
