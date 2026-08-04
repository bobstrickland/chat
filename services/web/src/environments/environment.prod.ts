/**
 * Production (deployed dev environment) — swapped in for `environment.ts` by
 * angular.json `fileReplacements` on the `production` configuration.
 *
 * These are the flattened, env-prefixed hostnames from CLAUDE.md "AWS
 * Environment" (single-level so one `*.chat.rstrickland.dev` ACM wildcard covers
 * them). **`dev-ws` does not exist yet** — there is no API Gateway WebSocket API
 * in Terraform — so this file is the intended target, not a verified one. Anyone
 * deploying should confirm the hostname against the actual apply.
 *
 * `wss://`, not `ws://`: the app is served over HTTPS, and a browser refuses a
 * plaintext WebSocket from a secure page (mixed content).
 */
export const environment = {
  production: true,

  wsUrl: 'wss://dev-ws.chat.rstrickland.dev',

  // Hosted UI: still the pool's default Cognito domain, because that is what is
  // actually applied. The custom `dev-auth.chat.rstrickland.dev` domain exists in
  // the Terraform module but was not part of the standalone apply that created
  // the live pool — switch this the day that changes.
  hostedUiDomain: 'https://chat-dev-local.auth.us-east-1.amazoncognito.com',
  webClientId: '3et8vk89kar6pg4lmbptug6nnd',
};
