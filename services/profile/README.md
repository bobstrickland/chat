# Profile Service

Owns user-facing profile data. Phase 2; extended in Phase 10.

Table: `profiles` (PK `userId`) — `profiles-local` under compose. Attributes:
`displayName`, `bio`, and (Phase 10) `avatarMediaId` (photo avatar — a Media-service
`mediaId`, replacing the old free `avatarUrl`), `phone`, `links` (≤10 URLs), `tags`
(≤10), `visibility` (`PUBLIC|CONTACTS|PRIVATE`, default PUBLIC). DynamoDB is schemaless,
so the new attributes need no table migration. The avatar photo is uploaded through the
**Media service** (shrunk ≤1024 like any image); Profile only stores its id.

Visibility gates **search** (Phase 10: Profile publishes displayName/phone/tags/visibility on
`search.index`; the Search indexer keeps only PUBLIC profiles) **and profile views** (Phase 11,
`core/getProfile.js`): PUBLIC → full to anyone; CONTACTS → full only if the owner added the
caller; PRIVATE → never full to others. An unauthorized read returns **basic identity**
(name + avatar, `restricted:true`) rather than 404, so the chat UI keeps rendering people you
share a conversation with — only the detail fields are gated (`core/basicIdentity.js`).

**Contacts (Phase 11):** Profile also owns the `contacts` table (PK `userId`, SK `contactId`)
and a self-only API — `GET /contacts`, `POST /contacts {contactId}`, `DELETE /contacts/{id}`.
`GetItem(owner, viewer)` is the CONTACTS-visibility check.

Source of truth for the base schema is `terraform/modules/dynamodb/main.tf`,
mirrored by `scripts/init_dynamodb.sh`.

## Language choice: Node.js 22

Per CLAUDE.md "Language / Runtime", Profile has **no default language** and the
choice must be justified here rather than assumed:

- **Stays on Lambda indefinitely.** Unlike Messaging and Presence, there is no
  Fargate migration planned — profile reads are bursty and low-volume, exactly
  the shape Lambda bills well for. A JVM's warm-up cost would never amortize,
  since there's no long-lived container to amortize it into.
- **Cold-start sensitive.** A profile fetch sits on the conversation render
  path; a multi-second Java cold start is user-visible. This is the same
  argument that kept Auth on Node.
- **No workload that favours Java.** It's thin CRUD over DynamoDB — no Kafka
  client (the reason Messaging/Presence chose Java), no image processing (the
  reason Media might), no CPU-bound work at all.
- **Reuses Auth's JWKS verification pattern** (`src/clients/jwksVerifier.js`)
  directly, rather than reimplementing token verification in a second language.

Java would be the right call here only if this service later absorbed
media-processing or high-throughput event consumption. It does neither.

## API

All user-facing routes require `Authorization: Bearer <token>`, verified
against the configured JWKS URL. **This service never calls Cognito** — it
only fetches public signing keys (CLAUDE.md "Cognito isolation").

| Route | Auth | Notes |
|---|---|---|
| `GET /profiles/me` | Bearer | Resolves `userId` from the token |
| `GET /profiles/:userId` | Bearer | Any authenticated user may read any profile |
| `PATCH /profiles/:userId` | Bearer | **Own profile only** (403 otherwise) |
| `DELETE /profiles/:userId` | Bearer | **Own profile only** (403 otherwise) |
| `POST /internal/profiles` | `x-internal-api-key` | Service-to-service; called by Auth |
| `GET /health` | none | |

Editable fields: `displayName` (≤64), `avatarUrl` (≤512), `bio` (≤512).
Everything else is server-owned. `null` clears an optional field.

Reads are open to any authenticated user because a chat UI must render names
and avatars for everyone in a conversation — so **profiles hold no private
data**. Anything sensitive stays in Auth.

There is no list/scan endpoint: the table has no GSI, and scanning a user
table is a performance trap. Profile discovery belongs to Search (Phase 9).

## Account provisioning

Auth's `postConfirmation` Cognito trigger calls `POST /internal/profiles`
after a user confirms their email. Authenticated with the
`PROFILE_INTERNAL_API_KEY` shared secret (compared in constant time).

Provisioning is **idempotent** — `createIfAbsent` uses a DynamoDB
`attribute_not_exists` condition, so a Cognito trigger retry cannot clobber a
profile the user has since edited. The call is best-effort on the Auth side: a
failure logs loudly but does not fail the user's confirmation.

**This is a stopgap.** Once MSK is in play (Phase 3+), Auth publishes
`user.registered` and Profile consumes it — removing this endpoint, the
shared secret, and the synchronous coupling together.

## Config

| Var | Required | Notes |
|---|---|---|
| `PROFILES_TABLE` | yes | `profiles-local` locally |
| `COGNITO_JWKS_URL` | yes | Public keys only; no Cognito API access |
| `PROFILE_INTERNAL_API_KEY` | yes | No fallback — fails loudly at startup |
| `DYNAMODB_ENDPOINT` | local only | Absent in AWS; SDK resolves the real endpoint |
| `AWS_REGION`, `PORT` | | |

## Run

```
docker compose up -d --build profile-service   # :3002 on the host
npm test                                       # integration; needs dynamodb-local
```

## Kafka auth (`KAFKA_AUTH`)

`plaintext` (default, and when unset) = local Redpanda, no TLS, no auth.
`iam` = TLS + SASL IAM, which real MSK requires (`client_broker=TLS` +
`sasl.iam=true`), so a plaintext client cannot connect to it at all. Set it to
`iam` for any AWS deployment; credentials come from the default AWS chain (the
task/Lambda role). Implementation: `clients/kafkaAuth` — SASL/OAUTHBEARER with a presigned SigV4 token, applied to the `search.index` producer.

## Kafka compression + log level

`clients/kafkaCompression.js` registers `kafkajs-snappy` for the `search.index` producer: kafkajs ships
GZIP only, and a Snappy record was previously a permanent poison pill (crash → restart →
re-read → crash, group `Empty`, consumption silently dead). Pure JS, so nothing native
enters a Lambda package. The client also runs at `logLevel.ERROR` rather than `NOTHING`,
so a crashed consumer or broker failure is actually visible. See
`services/search/README.md` for the full story.

## Secrets (`SECRETS_PROVIDER`)

`env` (default) reads `.env`; `awssm` fetches from AWS Secrets Manager, overriding
the environment. This service reads **`shared/profile-internal-api-key`** →
`PROFILE_INTERNAL_API_KEY`, the same secret Auth sends — see
`services/auth/README.md`.

Not `required`: the key guards only the internal provisioning endpoint, so a
failure to load leaves every bearer-authed route working and fails just that one.
