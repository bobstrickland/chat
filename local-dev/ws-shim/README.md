# ws-shim

Local stand-in for API Gateway WebSocket. **Dev-only — never deployed.**

Runs on `8090` (see `WS_SHIM_ENDPOINT` in the root `.env`).

## What it does

| Real API Gateway | Here |
|---|---|
| `$connect` / `$disconnect` / `$default` route → Lambda | `POST {WS_SHIM_ROUTE_TARGET}/ws` with an API-Gateway-shaped event |
| `postToConnection` | `POST /@connections/{id}` (body forwarded verbatim as a text frame) |
| `getConnection` | `GET /@connections/{id}` |
| `deleteConnection` | `DELETE /@connections/{id}` |
| `GoneException` on a stale connection | `410` from any `/@connections/{id}` call |

Plus `GET /health` and `GET /@connections` (connection list) as debug aids —
neither has an API Gateway equivalent.

## Config

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `8090` | Listen port |
| `WS_SHIM_ROUTE_TARGET` | *(unset)* | Base URL of the service handling route events. **Unset is expected until Phase 3** — connections still work, route events are logged and dropped. |
| `WS_SHIM_MANAGE_CONNECTIONS_PATH` | `/@connections` | Management API prefix |
| `WS_SHIM_STAGE` | `local` | Value for `requestContext.stage` |

Point `WS_SHIM_ROUTE_TARGET` at the Presence & Connection service
(`http://presence-service:3000`) once it exists.

## Fidelity gaps — revalidate against real API Gateway before calling anything done

- **No authorizer support.** Real `$connect` can run a Lambda/IAM/JWT authorizer;
  here every handshake is accepted unless the route target returns non-2xx.
- **No route selection expression.** Every client message goes to `$default`;
  real API Gateway can dispatch on a body field to named routes.
- **Connection id is handed to the client** via a `__shim.connected` frame on
  open. API Gateway does no such thing — the id only exists server-side. Don't
  build client logic that depends on knowing its own connectionId.
- **No idle/max connection timeouts** (real: 10 min idle, 2 hr max).
- **No frame size cap** (real: 128 KB) and no `PayloadTooLargeException`.
- **No IAM/SigV4 on the management API** — it's open HTTP on the compose network.
- **Single process, in-memory registry.** Connection state dies with the
  container and does not survive a restart.
