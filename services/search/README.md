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
  `{ kind:"profile", userId, displayName, bio }` to the declared `search.index`
  topic on create/update. Only `kind:"profile"` is understood today.
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
