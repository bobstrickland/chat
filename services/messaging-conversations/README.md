# Messaging & Conversations Service

Sends and stores messages; delivers them in real time. Phase 4. Owns the
single-table `conversations` table. **Java** (per CLAUDE.md — highest-traffic,
most logic-heavy service; early Fargate target; native Kafka client). Same
conventions as `services/presence-connection` (no Spring, JDK HttpServer, AWS
SDK v2, kafka-clients, nimbus JWKS, shade fat jar).

## Send vs. delivery — decoupled through message.sent

```
POST /messages ──▶ MessagingService: persist to Conversations, publish message.sent
                                                    │
                             message.sent (Kafka, keyed by conversationId)
                                                    │
                   KafkaDeliveryConsumer ──▶ DeliveryService: for each member != sender,
                                             getActiveConnections (Presence) → postToConnection (ws-shim)
```

Send does NOT deliver. Delivery is a consumer of `message.sent`, so
Notification (Phase 5) and Search (Phase 9) attach to the same event, and the
fan-out generalizes to groups (more members) and multi-device (more connections)
with no change. Locally the consumer runs on a daemon thread inside
`HttpServerMain`; in AWS it becomes an MSK-triggered Lambda over the same
`DeliveryService`.

## Data model (single-table `conversations`)

PK `conversationId`, SK one of:
- `meta` — `{type: "direct", createdAt}`
- `member#{userId}` — carries `userId` for `gsi-user-conversations`
- `ts#{sentAt}#{messageId}` — the message; the ISO-8601 instant sorts
  chronologically, so a range query returns messages in order.

1:1 conversation id is deterministic: `dm#{min(a,b)}#{max(a,b)}`, so both users
derive the same id with no lookup, and a direct conversation is unique.

## API

| Route | Auth | |
|---|---|---|
| `POST /messages` | Bearer | `{recipientId, body}` → the persisted message |
| `GET /conversations/direct/{peerId}/messages` | Bearer | `{conversationId, messages[]}` |
| `GET /health` | none | |

## Events

`message.sent`, keyed by `conversationId` (per-conversation ordering — the
guarantee delivery/search rely on). Payload:
`{conversationId, messageId, senderId, body, sentAt}`.

Delivery frame pushed to clients: same fields plus `type: "message"`.

## Gotchas

- **HTTP/1.1 is forced on the outbound HTTP clients** (`WsShimConnectionPusher`,
  `PresenceConnectionLookup`). The JDK HttpClient defaults to HTTP/2 and sends an
  h2c upgrade header; ws-shim's Node http server misreads that as a WebSocket
  upgrade and rejects the request. Real API Gateway wouldn't — it's a ws-shim
  quirk — but pin 1.1 for any Java→Node-http call.
- **Delivery consumer offset reset is `latest`** — on restart it must not replay
  old messages (those are in history). A message produced during the consumer's
  rejoin window is delivered from history on next fetch, not pushed live.
- Presence/push failures are best-effort: a message is already persisted, so a
  transient Presence outage degrades to "load from history," never a lost message.

## Build / run

```
mvn package                                       # fat jar + 9 core tests
docker compose up -d --build messaging-service    # :3003 host, :3000 in-network
```

Needs (`.env`): `CONVERSATIONS_TABLE`, `KAFKA_BROKERS`, `TOPIC_MESSAGE_SENT`,
`COGNITO_JWKS_URL`, `PRESENCE_SERVICE_URL`, `PRESENCE_INTERNAL_API_KEY`,
`WS_SHIM_ENDPOINT`, `WS_SHIM_MANAGE_CONNECTIONS_PATH`, `DYNAMODB_ENDPOINT` (local),
`AWS_REGION`, `PORT`.
