# Chat App

A mobile + web chat application built on AWS microservices. Real-time
messaging (1:1 and group), multi-device sync, read/delivery receipts,
media sharing, and search — designed KISS-first: **7 services**, one
datastore technology (DynamoDB), no service boundary that doesn't earn
its keep.


---

## Status

**Early build-out, local-first.** Currently standing up services against
a local Docker stack before deploying real AWS infrastructure. See
[`CLAUDE.md` → Current Status](./CLAUDE.md#current-status) for exactly
what's built and what's next — that section is kept up to date as the
single source of truth on build progress, this README is not.

---

## Architecture at a Glance

| Service | Responsibility |
|---|---|
| **Auth** | Registration, login, OAuth2 (Google/Apple), optional MFA — wraps Cognito |
| **Profile** | User profile data, settings, contacts |
| **Messaging & Conversations** | Send/receive (1:1 + group), membership/roles, read/delivery receipts, multi-device sync |
| **Presence & Connection** | WebSocket connection registry, online/offline/typing status |
| **Notification** | Push delivery (APNs / FCM / Web Push) when a recipient is offline |
| **Media** | Upload, transform, and serve images/video/audio |
| **Search** | Message and user search |

Clients (iOS, Android, Web) all hit the same REST/WebSocket API surface —
no parallel backend per platform.

Async communication between services runs on **Amazon MSK (Kafka)**.
Every service owns its own data; nothing reads another service's table
directly.

Full rationale for what's merged, what's kept separate, and why:
see design doc [Section 2](./chat-app-design-doc.md#2-service-consolidation-rationale).

---

## Tech Stack

- **Compute:** AWS Lambda (Fargate migration path built in for
  Messaging & Conversations and Presence & Connection — the two
  highest-traffic services)
- **Data:** DynamoDB only, single technology across every service
- **Async:** Amazon MSK (Kafka)
- **Auth:** Amazon Cognito (wrapped behind a swappable `IdentityProvider`
  interface — see [Auth Service](./services/auth/README.md))
- **Search:** OpenSearch
- **Edge:** API Gateway (REST + WebSocket), CloudFront, Route53/ACM
- **IaC:** Terraform (`terraform/`)
- **CI/CD:** GitHub Actions (OIDC) → VPC-attached CodeBuild for `terraform apply`

---

## Repo Structure

```
.
├── chat-app-design-doc.md      # Full architecture design document
├── CLAUDE.md                    # AI-assisted dev guidance + current build status
├── docker-compose.yml           # Local dev stack (DynamoDB Local, Redpanda, MinIO, etc.)
├── .env               # Env vars for local service containers
├── scripts/
│   └── init-dynamodb.sh         # Creates all DynamoDB Local tables (idempotent)
├── services/
│   └── auth/                    # First scaffolded service — see its own README
│       ├── src/core/            # Business logic, no AWS SDK imports
│       ├── src/providers/       # IdentityProvider + CognitoProvider + LocalAuthProvider
│       ├── src/adapters/        # httpServer.js (local/Fargate) + lambdaHandler.js (Lambda)
│       ├── src/clients/         # SDK clients, JWKS verifier
│       └── Dockerfile
├── terraform/
│   ├── modules/                 # vpc, iam, dynamodb, msk, cognito, dns_acm, ci_cd
│   ├── environments/dev/        # Thin root config wiring modules together (dev-only so far)
│   └── README.md                # Bootstrap ordering, Mongey/kafka provider requirements
└── .github/workflows/           # CI: terraform plan/apply via GitHub Actions → CodeBuild
```

Each service follows the same internal structure
(`core/` / `providers/` / `adapters/` / `clients/`) so that a Lambda→Fargate
migration is a wrapper swap, never a rewrite. See `CLAUDE.md` for the
non-negotiable rules behind this.

---

## Getting Started (Local Development)

Requires: Docker, Docker Compose V2 (`docker compose`, not the standalone
`docker-compose` binary), Node.js 22, AWS CLI (for local DynamoDB table
setup only — no real AWS account needed to develop locally).

```bash
# 1. Bring up local infra (only what you need — see docker-compose.yml)
docker compose up -d dynamodb-local

# 2. Create the DynamoDB tables
./scripts/init-dynamodb.sh

# 3. Bring up a service (auth-service is the first one scaffolded)
docker compose up -d auth-service

# 4. Smoke test
curl http://localhost:3001/health
curl -X POST http://localhost:3001/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"correcthorsebattery"}'
```

**Auth note:** local dev defaults to a real **dev Cognito User Pool**
(`AUTH_PROVIDER=cognito` in `.env`) rather than faking identity — see
[`CLAUDE.md` → Auth](./CLAUDE.md#auth) for why, and for the fully-offline
`AUTH_PROVIDER=local` fallback if you don't want any AWS dependency at all.

Full local dev details, known fidelity gaps (what doesn't behave
identically to real AWS), and troubleshooting notes: `CLAUDE.md` →
"Local Development."

---

## Deploying to AWS

Not yet done end-to-end as a full environment — Cognito has been applied
standalone against a real dev User Pool; the rest of `terraform/environments/dev`
is written and reviewed but not yet applied as a whole.

Before running it:
1. Read `terraform/README.md` in full — covers the state-bucket
   bootstrap ordering (chicken-and-egg on first apply) and the
   Mongey/kafka provider's VPC-reachability requirement.
2. Fill in `terraform/environments/dev/dev.tfvars` (`github_org`,
   `github_repo`, and OAuth2 credentials if using Google/Apple
   federation).

```bash
cd terraform/environments/dev
terraform init
terraform plan -var-file=dev.tfvars
terraform apply -var-file=dev.tfvars
```

