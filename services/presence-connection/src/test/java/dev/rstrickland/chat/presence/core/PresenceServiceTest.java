package dev.rstrickland.chat.presence.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * PresenceService is pure, so it tests with in-memory fakes — no DynamoDB, no
 * Kafka. If this test ever needs a real client, the core/adapters split has
 * been violated.
 */
class PresenceServiceTest {

  /** connectionId -> userId, preserving the (userId, connectionId) table shape. */
  static final class FakeRepo implements ConnectionRepository {
    final Map<String, Connection> byConnection = new LinkedHashMap<>();

    @Override
    public void save(Connection c) {
      byConnection.put(c.connectionId(), c);
    }

    @Override
    public Optional<String> findUserByConnection(String connectionId) {
      Connection c = byConnection.get(connectionId);
      return c == null ? Optional.empty() : Optional.of(c.userId());
    }

    @Override
    public void delete(String userId, String connectionId) {
      byConnection.remove(connectionId);
    }

    @Override
    public List<String> activeConnectionIds(String userId) {
      List<String> ids = new ArrayList<>();
      for (Connection c : byConnection.values()) {
        if (c.userId().equals(userId)) {
          ids.add(c.connectionId());
        }
      }
      return ids;
    }
  }

  record Emitted(String userId, String connectionId, String state, boolean online) {}

  static final class FakePublisher implements EventPublisher {
    final List<Emitted> events = new ArrayList<>();

    @Override
    public void connectionStateChanged(
        String userId, String connectionId, String state, boolean online) {
      events.add(new Emitted(userId, connectionId, state, online));
    }
  }

  private PresenceService service(FakeRepo repo, FakePublisher pub) {
    return new PresenceService(repo, pub, Duration.ofSeconds(7200));
  }

  @Test
  void connectPersistsAndEmitsOnline() {
    FakeRepo repo = new FakeRepo();
    FakePublisher pub = new FakePublisher();
    service(repo, pub).connect("u1", "c1", "web");

    assertEquals(List.of("c1"), repo.activeConnectionIds("u1"));
    assertEquals(1, pub.events.size());
    assertEquals(new Emitted("u1", "c1", "connected", true), pub.events.get(0));
  }

  @Test
  void connectionTtlIsSetInTheFuture() {
    FakeRepo repo = new FakeRepo();
    service(repo, new FakePublisher()).connect("u1", "c1", "web");
    Connection saved = repo.byConnection.get("c1");
    assertTrue(saved.expiresAt() > saved.connectedAt().getEpochSecond());
  }

  @Test
  void disconnectRemovesAndReportsStillOnlineWhenOtherConnectionsRemain() {
    FakeRepo repo = new FakeRepo();
    FakePublisher pub = new FakePublisher();
    PresenceService svc = service(repo, pub);

    svc.connect("u1", "web-1", "web"); // e.g. desktop
    svc.connect("u1", "mob-1", "ios"); // and phone
    svc.disconnect("web-1");

    assertEquals(List.of("mob-1"), repo.activeConnectionIds("u1"));
    Emitted last = pub.events.get(pub.events.size() - 1);
    assertEquals("disconnected", last.state());
    assertTrue(last.online(), "user still has the mobile connection");
  }

  @Test
  void disconnectOfLastConnectionReportsOffline() {
    FakeRepo repo = new FakeRepo();
    FakePublisher pub = new FakePublisher();
    PresenceService svc = service(repo, pub);

    svc.connect("u1", "c1", "web");
    svc.disconnect("c1");

    assertTrue(repo.activeConnectionIds("u1").isEmpty());
    Emitted last = pub.events.get(pub.events.size() - 1);
    assertEquals("disconnected", last.state());
    assertFalse(last.online(), "no connections left -> offline");
  }

  @Test
  void disconnectUnknownConnectionIsNoOp() {
    FakeRepo repo = new FakeRepo();
    FakePublisher pub = new FakePublisher();
    service(repo, pub).disconnect("ghost");
    assertTrue(pub.events.isEmpty(), "no event for an unknown connection");
  }

  @Test
  void isOnlineReflectsActiveConnections() {
    FakeRepo repo = new FakeRepo();
    PresenceService svc = service(repo, new FakePublisher());
    assertFalse(svc.isOnline("u1"));
    svc.connect("u1", "c1", "web");
    assertTrue(svc.isOnline("u1"));
  }
}
