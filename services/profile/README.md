# Profile Service

Owns user-facing profile data: display name, avatar, bio. Phase 2.

Table: `profiles` (PK `userId`) — `profiles-local` under compose.
Source of truth for the schema is `terraform/modules/dynamodb/main.tf`,
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
