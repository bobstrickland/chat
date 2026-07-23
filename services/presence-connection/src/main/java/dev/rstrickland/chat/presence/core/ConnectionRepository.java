package dev.rstrickland.chat.presence.core;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for connections. Implemented by clients/ against
 * DynamoDB; core depends only on this interface, never the AWS SDK.
 */
public interface ConnectionRepository {

  void save(Connection connection);

  /** Resolve a connectionId to its owning userId (via the connectionId GSI). */
  Optional<String> findUserByConnection(String connectionId);

  void delete(String userId, String connectionId);

  /** All currently-stored connectionIds for a user. */
  List<String> activeConnectionIds(String userId);
}
