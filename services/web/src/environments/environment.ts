/**
 * Development environment — the values the Angular dev server (`npm start`) runs
 * with. `environment.prod.ts` replaces this file at build time for the
 * `production` configuration (angular.json `fileReplacements`), so nothing here
 * ships in a production bundle.
 *
 * Only environment-VARYING values belong here. REST calls are deliberately
 * absent: they're origin-relative (`/auth/...`, `/profiles/...`), proxied by
 * `proxy.conf.json` in dev and by CloudFront behaviours once deployed, so they
 * need no base URL in either place. A WebSocket can't be origin-relative and
 * Cognito's Hosted UI lives on its own domain — hence exactly these three.
 */
export const environment = {
  production: false,

  /** ws-shim, the local stand-in for API Gateway WebSocket. */
  wsUrl: 'ws://localhost:8090',

  /** Cognito Hosted UI (Google sign-in) — dev pool, default Cognito domain. */
  hostedUiDomain: 'https://chat-dev-local.auth.us-east-1.amazoncognito.com',
  webClientId: '3et8vk89kar6pg4lmbptug6nnd',
};
