# Notification Service

Delivers messages to **offline** recipients via push. Phase 5. Owns the
`device-tokens` table. Runs on `:3005` (container `:3000`).

## Language: Node.js

Notification has no default language (CLAUDE.md "Language / Runtime") — the
choice is justified here. **Node, because of `web-push`:** it's the reference
Web Push (VAPID) library and correctly implements the VAPID JWT and RFC-8291
payload encryption. The deferred mobile paths also have strong Node libraries
(`firebase-admin` for FCM, `node-apn`/http2 for APNs). This is an event-driven
consumer with no CPU-bound work that would favour the JVM, so cold-start
sensitivity (it's a Lambda service) tips it to Node like Auth/Profile.

## How offline push works

```
Messaging DeliveryService: recipient has NO active connection
        │  publishes notification.trigger { recipientId, conversationId, messageId, senderId, body, sentAt }
        ▼
Notification consumer ─▶ deviceTokenRepository.listForUser(recipientId)
        │                   web devices → webPushSender (VAPID) → browser push endpoint
        │                   ios/android → deferred (logged, skipped)
        ▼
Browser service worker (public/sw.js) shows a system notification
```

Offline detection lives in **Messaging**, not here — it already looks up active
connections during delivery, so it emits the trigger with the specific offline
recipient. Notification never resolves conversation membership (that's
Messaging's data — No shared databases).

## API

| Route | Auth | |
|---|---|---|
| `POST /device-tokens` | Bearer | `{ deviceId, platform, subscription }` — register this device |
| `GET /push/config` | none | `{ publicKey }` — the VAPID public key, for the browser to subscribe |
| `GET /health` | none | |

Device tokens: PK `userId`, SK `deviceId`. `platform` (web/ios/android) drives
the send mechanism. For web, `subscription` is the browser PushSubscription.
Re-registering the same `deviceId` upserts. A push endpoint that returns 404/410
means the subscription is dead — it's pruned automatically.

## Config (`.env`)

`DEVICE_TOKENS_TABLE`, `KAFKA_BROKERS`, `TOPIC_NOTIFICATION_TRIGGER`,
`COGNITO_JWKS_URL`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`
(a `mailto:`), `DYNAMODB_ENDPOINT` (local), `AWS_REGION`, `PORT`.

Generate VAPID keys with `npx web-push generate-vapid-keys`.

## Testing note

The pipeline (trigger → consume → device lookup → web-push encrypt+send) is
verified end to end against the live stack. The final on-device notification
needs a real browser subscription (a real HTTPS push endpoint) — web-push is
HTTPS-only, so a plaintext capture server can't stand in for the last hop.
`docker-compose` gives this container `extra_hosts: host.docker.internal` so it
can reach external push endpoints (real ones are external anyway).

## Build / run

```
npm test                                          # 7 core tests
docker compose up -d --build notification-service # :3005
```
