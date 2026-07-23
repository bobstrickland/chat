/**
 * Cognito Hosted UI settings for the federated (Google) sign-in flow.
 *
 * Dev values, hardcoded for now. When the client is deployed these should come
 * from Angular's environment files (`environment.ts` / `environment.prod.ts`)
 * — the WS URL in realtime.service.ts has the same "hardcoded dev endpoint"
 * TODO, and both get parameterized together at deploy time.
 *
 * The pool uses Cognito's DEFAULT domain (not the custom dev-auth.* one) — see
 * CLAUDE.md "Auth".
 */
export const AUTH_CONFIG = {
  hostedUiDomain: 'https://chat-dev-local.auth.us-east-1.amazoncognito.com',
  webClientId: '3et8vk89kar6pg4lmbptug6nnd',
  scopes: ['email', 'openid', 'profile'],
  // NOT under /auth: that prefix proxies to the auth backend in dev (and to API
  // Gateway in prod), which would swallow the OAuth redirect. /oauth2 collides
  // with no proxy/behavior, so the SPA router handles it.
  callbackPath: '/oauth2/callback',
};

/**
 * The app's OAuth redirect target. Derived from the current origin so it's
 * `http://localhost:4200/auth/callback` in dev — which MUST be registered in
 * the Cognito app client's callback URLs (Terraform `web_callback_urls`), and
 * must match exactly the value sent to /auth/federated for the code exchange.
 */
export function oauthRedirectUri(): string {
  return `${location.origin}${AUTH_CONFIG.callbackPath}`;
}
