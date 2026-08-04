# Auth Service

Wraps identity behind `IdentityProvider` — see `src/providers/IdentityProvider.js`
for the contract. `CognitoProvider` is the real implementation; `LocalAuthProvider`
is an offline-only stub (no MFA, no OAuth2) selected via `AUTH_PROVIDER=local`.

## Structure

```
src/core/        pure business logic — no AWS SDK imports, no event-shape awareness
src/providers/   IdentityProvider interface + CognitoProvider + LocalAuthProvider
src/clients/     Cognito SDK client + shared JWKS verifier (reused by other services)
src/adapters/    httpServer.js (local/Fargate), lambdaHandler.js (Lambda), cognitoTriggers.js
```

## Run locally

Via docker-compose (from repo root):
```bash
docker-compose up -d auth-service
```

Standalone:
```bash
npm install
AUTH_PROVIDER=local npm start
```

## Env vars

See `local-env/.env` (or root `.env`, depending on your setup) for the full list.
Required for `AUTH_PROVIDER=cognito`: `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID_WEB`,
`COGNITO_REGION`, `COGNITO_JWKS_URL`, `COGNITO_HOSTED_UI_DOMAIN`.

## Not yet implemented

- Mobile client ID wiring in config.js (currently only reads `COGNITO_CLIENT_ID_WEB` —
  extend to select client ID per caller/platform before mobile testing)
- `postConfirmation` trigger's Profile Service call (Phase 2 of build sequence)
- Rate limiting on `/auth/mfa/verify` and `/auth/login` (add before any real exposure)

## Secrets (`SECRETS_PROVIDER`)

`env` (default) reads `.env`; `awssm` fetches from AWS Secrets Manager, overriding
the environment. This service reads **`shared/profile-internal-api-key`** →
`PROFILE_INTERNAL_API_KEY`.

It's under `shared/`, not `auth/`, because the Profile service verifies the same
value — one stored secret means caller and verifier can't drift apart on a
rotation. `terraform/modules/iam` grants that individual secret to exactly these
two roles (the blanket `secret:{service}/*` grant deliberately doesn't cover it).

Loaded by `clients/secretsLoader.js` into `process.env` before dependencies are
built. Not `required`: Auth→Profile provisioning is best-effort by design, and
lazy provisioning covers the gap.
