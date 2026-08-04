# Notification Service

Delivers messages to **offline** recipients via push. Phase 5. Owns the
`device-tokens` table. Runs on `:3005` (container `:3000`).

## Language: Node.js

Notification has no default language (CLAUDE.md "Language / Runtime") — the
choice is justified here. **Node, because of `web-push`:** it's the reference
Web Push (VAPID) library and correctly implements the VAPID JWT and RFC-8291
payload encryption. This is an event-driven
consumer with no CPU-bound work that would favour the JVM, so cold-start
sensitivity (it's a Lambda service) tips it to Node like Auth/Profile.

## How offline push works

```
Messaging DeliveryService: recipient has NO active connection
        │  publishes notification.trigger { recipientId, conversationId, messageId, senderId, body, sentAt }
        ▼
Notification consumer ─▶ deviceTokenRepository.listForUser(recipientId)
        │                   web     → webPushSender (VAPID)   → browser push endpoint
        │                   android → fcmSender (FCM HTTP v1) → Google → device
        │                   ios     → skipped (no iOS client, per the rules)
        ▼
Browser service worker (public/sw.js) / Android tray notification
```

Offline detection lives in **Messaging**, not here — it already looks up active
connections during delivery, so it emits the trigger with the specific offline
recipient. Notification never resolves conversation membership (that's
Messaging's data — No shared databases).

## API

| Route | Auth | |
|---|---|---|
| `POST /device-tokens` | Bearer | `{ deviceId, platform, subscription }` — register this device |
| `DELETE /device-tokens/{deviceId}` | Bearer | unregister on sign-out (self-only, idempotent) |
| `GET /push/config` | none | `{ publicKey }` — the VAPID public key, for the browser to subscribe |
| `GET /health` | none | |

Device tokens: PK `userId`, SK `deviceId`. `platform` (web/ios/android) drives
the send mechanism. For web, `subscription` is the browser PushSubscription; for
android it's `{ token }`, the FCM registration token. Re-registering the same
`deviceId` upserts. A dead token — a web endpoint returning 404/410, or FCM
reporting the registration token unregistered — is pruned automatically.

**Why `DELETE` exists:** pruning only fires when a token goes *dead*, and a token
is very much alive when its owner signs out. Without an explicit unregister, the
device stays registered to whoever signed in first, and the next user of that
phone would receive the previous user's offline pushes. The Android client calls
it during sign-out, before clearing its tokens (the call is bearer-authed), and
so does the web client (`PushService.unregister`). Both also call it before
account deletion: Messaging doesn't know a user is gone, so a peer sending into
the old conversation would still fire a trigger at the departed user's devices.

## Android push (FCM)

`clients/fcmSender.js` speaks **FCM HTTP v1** directly: a service-account JWT
exchanged for an OAuth2 access token, then one JSON POST per message.

**Why no `firebase-admin`.** It's the obvious library, and it would drag ~65 MB
of transitive `@google-cloud/*` packages into a Lambda deploy package to do an
OAuth2 exchange and an HTTP POST. Compare `web-push`, which earns its place by
implementing RFC-8291 payload encryption — real cryptography. Signing a JWT with
`node:crypto` is fifteen lines, so here the dependency buys nothing. (Both HTTP
calls are `fetch`-injectable, which is how `test/fcmSender.test.js` covers the
token exchange, the v1 message shape and dead-token detection without Firebase.)

Each push carries **both** a `notification` and a `data` block, deliberately:

- app **killed or backgrounded** → Play services draws the tray notification
  itself (our process may not exist — which is the whole point of offline push),
  and `data` rides along as intent extras so the tap still opens the right
  conversation;
- app in the **foreground** → `onMessageReceived` fires instead, and the client
  builds the notification from `data`.

A data-only message would be dropped in the killed case. `android.priority` is
`high` so Doze doesn't sit on a chat message, and `channel_id` must match a
channel the app has created or the notification is discarded silently.

FCM's "stop sending to this token" replies (404/`UNREGISTERED`,
`SENDER_ID_MISMATCH`, 400/`INVALID_ARGUMENT`) map to `{ gone: true }` — the same
shape `webPushSender` uses for a 404/410 — so one prune path covers both.

### Turning it on

1. Firebase console → your project → **Project settings → Service accounts →
   Generate new private key**. Save the JSON as `secrets/fcm-service-account.json`
   (gitignored).
2. Uncomment the `volumes:` line on `notification-service` in
   `docker-compose.yaml` so the container can read it.
3. Set `FCM_ENABLED=true` in `.env`, then
   `docker compose up -d --build notification-service`.
4. The Android app needs the matching `app/google-services.json` from the *same*
   Firebase project — see `android/README.md`.

**Not configured is a supported state, not an error.** With `FCM_ENABLED=false`
(or credentials missing/unreadable) the service logs why, disables the android
branch, and android devices are counted as `skipped` — never `failed`, and never
pruned. Web push is unaffected. This is the normal local-dev state.

## Config (`.env`)

`DEVICE_TOKENS_TABLE`, `KAFKA_BROKERS`, `TOPIC_NOTIFICATION_TRIGGER`,
`COGNITO_JWKS_URL`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`
(a `mailto:`), `DYNAMODB_ENDPOINT` (local), `AWS_REGION`, `PORT`.

FCM: `FCM_ENABLED`, and either `FCM_SERVICE_ACCOUNT_FILE` (a path inside the
container) or `FCM_SERVICE_ACCOUNT_JSON` (inline). Optional
`FCM_PROJECT_ID` (defaults to the service account's own `project_id`) and
`FCM_ANDROID_CHANNEL_ID` (default `messages` — must match the app's channel).

Generate VAPID keys with `npx web-push generate-vapid-keys`.

## Testing note

The pipeline (trigger → consume → device lookup → web-push encrypt+send) is
verified end to end against the live stack. The final on-device notification
needs a real browser subscription (a real HTTPS push endpoint) — web-push is
HTTPS-only, so a plaintext capture server can't stand in for the last hop.
`docker-compose` gives this container `extra_hosts: host.docker.internal` so it
can reach external push endpoints (real ones are external anyway).

**FCM, verified against the live stack (2026-08-01):** an android device row →
`notification.trigger` → `devices=1 skipped=1 failed=0 pruned=0` with FCM
disabled (the supported off state), and with FCM enabled against a throwaway
service account the send path runs for real — signs the JWT, calls Google's token
endpoint, and reports `failed=1 "Invalid grant: account not found"` rather than
pruning a token over an infrastructure problem. Everything up to a genuine
Firebase credential is exercised; the last hop (Google → device) needs a real
project and a real handset, same limitation as web push's final hop.

## Build / run

```
npm test                                          # 20 tests (core + the FCM client)
docker compose up -d --build notification-service # :3005
```

## Kafka auth (`KAFKA_AUTH`)

`plaintext` (default, and when unset) = local Redpanda, no TLS, no auth.
`iam` = TLS + SASL IAM, which real MSK requires (`client_broker=TLS` +
`sasl.iam=true`), so a plaintext client cannot connect to it at all. Set it to
`iam` for any AWS deployment; credentials come from the default AWS chain (the
task/Lambda role). Implementation: `clients/kafkaAuth` — kafkajs has no AWS_MSK_IAM mechanism, so this uses SASL/OAUTHBEARER with a presigned SigV4 token (`aws-msk-iam-sasl-signer-js`). Same protocol as the Java services, different-looking config.

## Kafka compression + log level

`clients/kafkaCompression.js` registers `kafkajs-snappy` for the `notification.trigger` consumer: kafkajs ships
GZIP only, and a Snappy record was previously a permanent poison pill (crash → restart →
re-read → crash, group `Empty`, consumption silently dead). Pure JS, so nothing native
enters a Lambda package. The client also runs at `logLevel.ERROR` rather than `NOTHING`,
so a crashed consumer or broker failure is actually visible. See
`services/search/README.md` for the full story.

## Secrets (`SECRETS_PROVIDER`)

| Value | Behaviour |
|---|---|
| `env` (default, incl. blank) | values come from `.env` — local dev, unchanged |
| `awssm` | fetched from AWS Secrets Manager, **overriding** the environment |

Secrets read by this service:

| Secret | Lands in |
|---|---|
| `notification/vapid` | `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` |
| `notification/fcm-service-account` | `FCM_SERVICE_ACCOUNT_JSON` (verbatim) |

The FCM secret is loaded **whole**, not destructured: it's a Google
service-account document whose PEM private key would have its newlines mangled by
a round-trip through individual fields. Verified — the loaded key keeps its
newlines.

`clients/secretsLoader.js` hydrates `process.env` before `getDependencies()` runs
(`await loadSecrets()` in both adapters). Hydrating rather than returning values
keeps the async boundary in the adapter instead of making `getDependencies()` and
every caller async for two values — and leaves the local path as literally
unchanged code.

A secret that can't be read logs a warning and falls back to the environment;
neither of these is `required`, because both features degrade rather than break
and a dead service is worse than no push. An unrecognised `SECRETS_PROVIDER`
throws at startup — silently using `.env` values in AWS is what this exists to
prevent.

**Gotcha:** an INVALID VAPID key kills the service at startup, because
`web-push` validates the keypair in `setVapidDetails`. That's pre-existing (a bad
key in `.env` does the same), but it means a malformed secret is fatal where a
missing one is not.
