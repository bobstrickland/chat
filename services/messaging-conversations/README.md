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

## Kafka auth (`KAFKA_AUTH`)

`plaintext` (default, and when unset) = local Redpanda, no TLS, no auth.
`iam` = TLS + SASL IAM, which real MSK requires (`client_broker=TLS` +
`sasl.iam=true`), so a plaintext client cannot connect to it at all. Set it to
`iam` for any AWS deployment; credentials come from the default AWS chain (the
task/Lambda role). Implementation: `clients/KafkaSecurity` (AWS_MSK_IAM mechanism), applied to the producer and the delivery consumer.

## WebSocket push provider (`WS_PROVIDER`)

Delivery, receipts and deletions all push frames through one `core/ConnectionPusher`.
Two implementations, selected by `clients/ConnectionPushers.create`:

| `WS_PROVIDER` | Implementation | Needs |
|---|---|---|
| `shim` (default, unset) | `clients/WsShimConnectionPusher` | `WS_SHIM_ENDPOINT`, `WS_SHIM_MANAGE_CONNECTIONS_PATH` |
| `apigateway` | `clients/ApiGatewayConnectionPusher` | `WS_MANAGEMENT_ENDPOINT`, `AWS_REGION` |

`WS_MANAGEMENT_ENDPOINT` is the **HTTPS management** endpoint of the WebSocket API and
stage — `https://{api-id}.execute-api.{region}.amazonaws.com/{stage}` — not the `wss://`
URL clients connect to. The SDK appends `/@connections/{connectionId}`. Passing a `wss://`
URL is rejected at startup, because otherwise it surfaces as opaque 403s at push time.

Needs `execute-api:ManageConnections`, already granted by `terraform/modules/iam`
(`ws_manage_connections`).

Notes:

- **`WS_SHIM_ENDPOINT` is not `require()`d** — it doesn't exist in AWS. Each provider
  validates its own inputs, so a missing variable still fails fast naming the right one.
- An unrecognised `WS_PROVIDER` throws at startup rather than defaulting to `shim`. Failed
  pushes are deliberately swallowed (`push` returns false so one dead connection can't break
  a fan-out), so a typo would otherwise look like intermittently missing messages.
- Only the AWS client maps `GoneException` → `false`. A stale presence row is **Presence's**
  to clean up — it owns that table and it has a TTL; messaging must not write it.
- The HTTP/1.1 pinning in the shim client is a ws-shim quirk (its Node server misreads the
  JDK client's h2c upgrade header) and deliberately has no counterpart in the AWS client.

## Secrets (`SECRETS_PROVIDER`)

`env` (default, incl. blank) uses the `.env` value; `awssm` reads
**`shared/presence-internal-api-key`** from AWS Secrets Manager (field `apiKey`)
and falls back to the environment if it can't. This service sends that key.

One stored value shared by both ends, so a rotation can't leave caller and
verifier disagreeing; `terraform/modules/iam` grants that individual secret to
exactly these two roles. Implementation: `clients/SecretsLoader` (the Java
counterpart to the Node services' `clients/secretsLoader.js`, same env switch).
Resolution happens once at startup, so a rotated secret needs a restart.

