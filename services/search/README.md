# Search Service

Full-text search over chat **messages** and user **profiles**, backed by
OpenSearch. Consumes events to keep the index fresh; serves `GET /search`.
Runs on `:3007`.

## Language: Node

CLAUDE.md ("a thin OpenSearch proxy for Search has low overhead in either
language"): Search is a thin indexer + query proxy with no CPU-bound work, so
Node — reusing the existing Node conventions (express, `jwks-rsa` verifier,
`kafkajs` consumer, `config.js` dependency bundle, `core/adapters/clients`).

## Indexing pipeline (event-driven, no table reads)

```
message.sent  ──▶ search-messages consumer ──▶ core/indexMessage ─┐
                                                                   ├─▶ OpenSearch
search.index  ──▶ search-index    consumer ──▶ core/indexProfile ─┘   (upsert by id)
```

- **Messages**: consumes the existing `message.sent` topic (the fan-out point the
  Messaging service already publishes to). Media-only messages (blank body) are
  skipped — nothing to full-text search.
- **Profiles**: the Profile service publishes a generic indexing envelope
  `{ kind:"profile", userId, displayName, phone, tags, visibility }` to the declared
  `search.index` topic on create/update. Only `kind:"profile"` is understood today.
  **Visibility (Phase 10):** only **PUBLIC** profiles are kept searchable — a non-PUBLIC
  profile is *deleted* from the index (so a PUBLIC→PRIVATE flip removes it), which makes
  "only PUBLIC ever surfaces" true by construction. Searchable fields are display name,
  **tags**, and **phone** (matched on a digits-only normalization so formatting doesn't
  matter); bio is not searched. The query also filters `visibility=PUBLIC` as a backstop.
- Both consumers use **`fromBeginning: true`** (earliest) — the index must be
  *complete*, so a fresh deploy backfills the retained log. Indexing is an
  idempotent upsert (doc id = messageId / userId), so replay never duplicates.
  (Contrast the messaging DELIVERY consumer, which uses `latest` — it wants only
  live traffic.)

## Search + authorization

`GET /search?q=...&type=messages|users|all` (bearer auth).

- **messages**: full-text on `body`, **hard-scoped to the caller's own
  conversations**. Membership is owned by Messaging, not duplicated here, so
  Search calls Messaging `GET /conversations` with the *caller's own token* to
  get their conversationIds, then filters the OpenSearch query to exactly those.
  No conversations → no results (never "all messages").
- **users**: full-text on `displayName`/`bio` (a public people directory).

## Run

```
npm install
npm test                                        # core tests (fakes; no OpenSearch)
docker compose up -d --build search-service     # :3007
```

Indices are created on boot (idempotent, create-if-missing) with retry while
OpenSearch warms up.

Config (`.env`): `OPENSEARCH_ENDPOINT`, `KAFKA_BROKERS`, `TOPIC_MESSAGE_SENT`,
`TOPIC_SEARCH_INDEX`, `MESSAGING_SERVICE_URL`, `COGNITO_JWKS_URL`, `PORT`.
Optional: `SEARCH_MESSAGES_INDEX`/`SEARCH_PROFILES_INDEX` (index names),
`SEARCH_MESSAGE_GROUP`/`SEARCH_INDEX_GROUP` (consumer groups).

## AWS

`adapters/lambdaHandler.js`: `handler` (API Gateway `GET /search`),
`messageHandler` / `indexHandler` (MSK-triggered indexers) — same cores.
OpenSearch domain replaces the local container; index/query calls are unchanged.

## Kafka auth (`KAFKA_AUTH`)

`plaintext` (default, and when unset) = local Redpanda, no TLS, no auth.
`iam` = TLS + SASL IAM, which real MSK requires (`client_broker=TLS` +
`sasl.iam=true`), so a plaintext client cannot connect to it at all. Set it to
`iam` for any AWS deployment; credentials come from the default AWS chain (the
task/Lambda role). Implementation: `clients/kafkaAuth` — SASL/OAUTHBEARER with a presigned SigV4 token (`aws-msk-iam-sasl-signer-js`), the kafkajs equivalent of the Java AWS_MSK_IAM mechanism.

## OpenSearch transport auth (`OPENSEARCH_AUTH`)

| Value | Behaviour |
|---|---|
| `none` (default, incl. blank/unset) | plain HTTP, unsigned — the local container runs `DISABLE_SECURITY_PLUGIN`, so there's nothing to authenticate to |
| `iam` | every request SigV4-signed via `AwsSigv4Signer`; a real managed domain 403s unsigned requests |

`OPENSEARCH_SERVICE` picks the signing service name: **`es`** for a provisioned
domain (default), **`aoss`** for OpenSearch Serverless. Getting it wrong yields a
signature mismatch rather than a useful error, so it's validated at startup —
along with `AWS_REGION`, whose absence the signer otherwise reports only as
"Region cannot be empty".

Implementation: `clients/openSearchAuth.js`, spread into the `Client`
constructor in `clients/openSearchClient.js`. The signer works by REPLACING the
Connection and Transport classes, so it has to be applied at construction — it
can't be added per request.

`@aws-sdk/credential-provider-node` is an explicit dependency: the signer
discovers it by dynamic import, and if it's only present transitively the failure
mode is a runtime "Unable to find a valid AWS SDK" the first time anything is
indexed.

Unrecognised values throw at startup rather than falling back to unsigned —
against a real domain that fallback would 403 every request, which looks like
"search returns nothing" rather than a misconfiguration.

## Kafka compression and log level (fixed 2026-08-04)

kafkajs ships **GZIP only**, and a Snappy-compressed record used to be a permanent
poison pill: `KafkaJSNotImplemented` → restart → re-read the same record → crash,
so the group ended up `Empty` and indexing stopped **silently**, because the client
ran at `logLevel.NOTHING`. The Java services consumed the identical records fine
(kafka-clients has Snappy built in) — an asymmetry that would have bitten in AWS,
where MSK topics default to `compression.type=producer`.

Both halves are fixed:

- `clients/kafkaCompression.js` registers `kafkajs-snappy` (pure JS — it depends on
  `snappyjs`, not the native `snappy` binding, so no build toolchain and no
  platform binaries in a Lambda package). Codecs live in a kafkajs-global
  registry, so registration is process-wide and idempotent.
- The client log level is now **`ERROR`**, not `NOTHING`. A crashed consumer or a
  broker failure is reported at ERROR, and suppressing it was what made a wedged
  consumer look like a perfectly healthy service. Verified quiet in steady state.

Verified by producing the exact poison record that caused the original outage
(`rpk topic produce --compression snappy`): consumed, indexed, group `Stable`,
lag 0.

