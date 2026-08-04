# Web client

The chat product's web client — Angular 22 (standalone components, signals,
RxJS). See `../../CLAUDE.md` "Client strategy" for why this is web-first and a
real product client, not a test harness.

## Why Angular

Chosen for robustness over the full 11-phase arc (real-time messaging, presence,
receipts, media, search): mandatory TypeScript, enforced structure, RxJS for the
streaming/real-time phases, and HttpClient interceptors for JWT handling.
Robustness was prioritized over minimal code.

## Local development

The backends run in docker-compose (auth `:3001`, profile `:3002`). The Angular
dev server proxies to them so the browser sees one same-origin host — no CORS,
and no backend CORS config needed.

```
# from repo root: make sure the stack is up and tables exist
docker compose up -d dynamodb-local auth-service profile-service
bash scripts/init_dynamodb.sh

# then, in services/web:
npm install      # first time only
npm start        # ng serve + proxy.conf.json, on http://localhost:4200
```

Proxy routes (`proxy.conf.json`): `/auth` → 3001, `/profiles` → 3002. This
mirrors how prod routes the `dev-auth.*` / `dev-api.*` subdomains, so the client
code uses the same relative paths in both.

## What works (Phase 1 auth screens + Phase 2 profile)

| Route | Screen |
|---|---|
| `/register` | Create account |
| `/login` | Sign in, incl. the **TOTP MFA challenge** step |
| `/mfa-enroll` | Enable 2FA (protected) |
| `/profile` | **View/edit your profile** (protected) — the Phase 2 deliverable |

Routes under a guard redirect to `/login` when unauthenticated. The `authGuard`
+ `authInterceptor` pair attaches the bearer token and, on a 401, does a
single-flight token refresh and retries before giving up.

**Exercising the full flow needs a confirmed user.** New registrations sit
UNCONFIRMED (no inbox wired locally), so `admin-confirm-sign-up` them first
(see CLAUDE.md). The existing dev fixture user is confirmed and MFA-enrolled, so
login → MFA → profile works end to end with it.

**Profiles** are provisioned by Auth's `postConfirmation` trigger, which isn't
attached to the pool yet — so a manually-confirmed account may have no profile,
and `/profile` shows an explained "no profile" state rather than an error until
one is provisioned via `POST /internal/profiles`.

## Structure

```
src/app/
  core/          services, models, interceptor, guard, token store — no UI
  features/      one folder per screen (register, login, mfa-enroll, profile)
  app.*          shell (nav reacts to auth state) + routes + config
```

`core/` holds all API/state logic; `features/` are thin components over it —
the same core/adapters instinct as the backend services.

## Build / deploy

`npm run build` → static bundle in `dist/web/browser`. The `Dockerfile`
(multi-stage → nginx) packages that bundle. In production the bundle sits behind
CloudFront/API Gateway, which handles API routing — the image itself only serves
static files and is **not** used for local dev.

## Environment configuration

`src/environments/environment.ts` (dev) and `environment.prod.ts` (deployed),
swapped by angular.json `fileReplacements` on the `production` configuration.

Only three values live there — `wsUrl`, `hostedUiDomain`, `webClientId` —
consumed by `core/realtime.service.ts` and `core/auth-config.ts`. **REST calls are
deliberately absent**: they're origin-relative (`/auth/…`, `/profiles/…`), proxied
by `proxy.conf.json` in dev and by CloudFront behaviours once deployed, so they
need no base URL in either place. A WebSocket URL can't be origin-relative and
Cognito's Hosted UI is on its own domain — hence exactly those three.

Verified both ways: a production build contains `wss://dev-ws…` and NOT
`ws://localhost:8090`; a development build is the reverse.

**`dev-ws.chat.rstrickland.dev` does not exist yet** — there is no API Gateway
WebSocket API in Terraform. The prod file records the intended target.

