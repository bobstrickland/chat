import { environment } from '../../environments/environment';

/**
 * Cognito Hosted UI settings for the federated (Google) sign-in flow.
 *
 * The environment-varying values (pool domain, app client id) come from
 * `src/environments/`, swapped by angular.json `fileReplacements` on a production
 * build. `scopes` and `callbackPath` are properties of THIS app rather than of an
 * environment, so they stay here.
 *
 * The pool uses Cognito's DEFAULT domain (not the custom dev-auth.* one) — see
 * CLAUDE.md "Auth".
 */
export const AUTH_CONFIG = {
  hostedUiDomain: environment.hostedUiDomain,
  webClientId: environment.webClientId,
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
