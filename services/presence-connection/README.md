# Presence & Connection Service

Tracks active WebSocket connections and online/offline status. Phase 3.
Owns the `presence-connections` table. **Java** (see Language choice below).

## Language: Java

Per CLAUDE.md "Language / Runtime", Presence is Java: it's an early Lambda→Fargate
migration target (connection-heavy steady traffic, warm JVM once migrated) and
benefits from the native Kafka client. **No Spring** — plain
`com.sun.net.httpserver` keeps the dependency graph and cold-start minimal, which
matters while it's still on Lambda. Same `core/adapters/clients` split as the Node
services, translated:

```
core/      PresenceService, Connection, ConnectionRepository, EventPublisher — pure, no SDK
clients/   DynamoConnectionRepository (SDK v2), KafkaEventPublisher, JwksTokenVerifier
adapters/  HttpServerMain (local/Fargate) + LambdaHandler (API GW WS) + WebSocketRouter (shared)
Config.java  manual DI wiring (the config.js analogue)
```

## How a connection flows

1. Browser opens `ws://<ws-shim>?token=<jwt>&device=web`.
2. ws-shim sends a `$connect` route event (with `queryStringParameters`) to `POST /ws`.
3. `WebSocketRouter` verifies the JWT via **JWKS only** (never calls Cognito —
   isolation rule), extracts the userId, and `PresenceService.connect` writes a
   row and publishes `connection.state.changed { state: connected, online: true }`.
   A non-2xx from `$connect` makes ws-shim reject the handshake — that's how an
   unauthenticated socket is refused.
4. On close, ws-shim sends `$disconnect` (connectionId only). `gsi-connection`
   resolves it to the userId; the row is deleted and a `disconnected` event is
   published with `online` = whether any of the user's other connections remain.

## Table

`presence-connections`: PK `userId`, SK `connectionId`, TTL on `expiresAt`
(safety net for missed disconnects), GSI `gsi-connection` (PK `connectionId`,
KEYS_ONLY) for the connectionId→userId lookup. A user may hold several
connections at once (web + mobile) — one row each.

## API

| Route | Auth | For |
|---|---|---|
| `POST /ws` | (handshake token) | ws-shim / API GW route events |
| `GET /presence/status/{userId}` | Bearer | web client — `{ userId, online }` |
| `GET /internal/presence/{userId}/connections` | `x-internal-api-key` | Messaging's delivery path (getActiveConnections) |
| `GET /health` | none | |

## Events

`connection.state.changed`, keyed by `userId` (per-user ordering; not
conversation-scoped, so not keyed by conversationId). Payload:
`{ userId, connectionId, state: "connected"|"disconnected", online, at }`.

## Build / run

```
mvn package                 # fat jar at target/presence-connection.jar; runs the 6 core tests
docker compose up -d --build presence-service   # :3004 on the host, :3000 in-network
```

Config via env (`.env`): `PRESENCE_CONNECTIONS_TABLE`, `KAFKA_BROKERS`,
`TOPIC_CONNECTION_STATE_CHANGED`, `COGNITO_JWKS_URL`, `PRESENCE_INTERNAL_API_KEY`,
`CONNECTION_TTL_SECONDS`, `DYNAMODB_ENDPOINT` (local only), `AWS_REGION`, `PORT`.

## Not yet

- WebSocket behavior is exercised via ws-shim, not real API Gateway — revalidate
  before considering it production-done (CLAUDE.md fidelity gaps).
- `LambdaHandler` handles the WS routes; the HTTP query endpoints would get their
  own HTTP-API Lambda route when deployed (Phase 10). Locally, HttpServerMain
  serves everything.
- MSK IAM auth isn't exercised (Redpanda is plaintext).
