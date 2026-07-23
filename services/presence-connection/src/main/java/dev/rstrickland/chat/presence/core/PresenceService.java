package dev.rstrickland.chat.presence.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Presence business logic — pure. No AWS SDK, no HTTP/event shapes, no JWT
 * parsing (the adapter verifies the token and hands us a userId). Everything it
 * touches is behind the ConnectionRepository / EventPublisher interfaces, so it
 * unit-tests with plain in-memory fakes.
 */
public final class PresenceService {

  private final ConnectionRepository repository;
  private final EventPublisher publisher;
  private final Duration ttl;

  public PresenceService(ConnectionRepository repository, EventPublisher publisher, Duration ttl) {
    this.repository = repository;
    this.publisher = publisher;
    this.ttl = ttl;
  }

  /**
   * Register a new connection. The user is online by definition afterwards, so
   * we always emit online=true. Consumers that only care about the
   * offline→online edge can dedupe on their side.
   */
  public void connect(String userId, String connectionId, String device) {
    Instant now = Instant.now();
    Connection connection =
        new Connection(userId, connectionId, device, now, now.plus(ttl).getEpochSecond());
    repository.save(connection);
    publisher.connectionStateChanged(userId, connectionId, "connected", true);
  }

  /**
   * Remove a connection, identified only by connectionId (all $disconnect gives
   * us). Unknown/already-reaped connections are a no-op — disconnect must be
   * idempotent, since a TTL expiry and an explicit close can race.
   *
   * `online` reflects whether the user has OTHER connections still open, which
   * is the offline transition Notification needs to decide whether to push.
   */
  public void disconnect(String connectionId) {
    repository
        .findUserByConnection(connectionId)
        .ifPresent(
            userId -> {
              repository.delete(userId, connectionId);
              boolean stillOnline = !repository.activeConnectionIds(userId).isEmpty();
              publisher.connectionStateChanged(userId, connectionId, "disconnected", stillOnline);
            });
  }

  /** For Messaging's delivery path: where to push a message for this user. */
  public List<String> getActiveConnections(String userId) {
    return repository.activeConnectionIds(userId);
  }

  public boolean isOnline(String userId) {
    return !repository.activeConnectionIds(userId).isEmpty();
  }
}
