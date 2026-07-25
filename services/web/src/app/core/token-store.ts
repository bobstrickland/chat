import { Injectable, computed, signal } from '@angular/core';
import { Tokens } from './models';

const STORAGE_KEY = 'chat.tokens';

interface JwtClaims {
  sub?: string;
  scope?: string;
  [claim: string]: unknown;
}

/** Decode a JWT payload (base64url) into claims — client-side reads only, never trusted for auth. */
function decodeJwt(token: string | null | undefined): JwtClaims | null {
  if (!token) return null;
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(payload)) as JwtClaims;
  } catch {
    return null;
  }
}

/**
 * Holds the session's tokens and persists them to localStorage so a page
 * reload keeps you logged in.
 *
 * Dev-only storage choice: localStorage is readable by any script on the
 * origin, so it's vulnerable to XSS token theft. Fine for local dev; a real
 * deployment should move to httpOnly cookies or in-memory + silent refresh.
 * Flagged here so it isn't mistaken for a production-ready decision.
 *
 * State is exposed as signals (Angular's reactive primitive). If you're coming
 * from RxJava: a `signal` is closest to a `BehaviorSubject` — it always has a
 * current value and notifies on change — but it's read synchronously as
 * `tokens()` rather than subscribed. Templates re-render automatically when it
 * changes.
 */
@Injectable({ providedIn: 'root' })
export class TokenStore {
  private readonly _tokens = signal<Tokens | null>(this.load());

  readonly tokens = this._tokens.asReadonly();
  readonly isAuthenticated = computed(() => this._tokens() !== null);

  /**
   * Whether this session can self-manage TOTP MFA. Cognito's
   * AssociateSoftwareToken/SetUserMFAPreference require the access token to
   * carry the `aws.cognito.signin.user.admin` scope, which only email/password
   * (USER_PASSWORD_AUTH) logins get. Federated (Google) logins go through the
   * Hosted UI and carry only `openid/profile/email`, so TOTP self-enrollment
   * isn't available to them (and Cognito MFA wouldn't gate a Google sign-in
   * anyway). The nav + 2FA screen use this to hide/explain rather than 500.
   */
  readonly canManageMfa = computed(() => {
    const claims = decodeJwt(this._tokens()?.accessToken);
    return String(claims?.scope ?? '')
      .split(' ')
      .includes('aws.cognito.signin.user.admin');
  });

  get accessToken(): string | null {
    return this._tokens()?.accessToken ?? null;
  }

  get refreshToken(): string | null {
    return this._tokens()?.refreshToken ?? null;
  }

  /**
   * The signed-in user's id (`sub`), decoded from the access token. Used
   * client-side only to tell "my" messages from others' — never for
   * authorization, which is always the server's job from the verified token.
   */
  get userId(): string | null {
    return decodeJwt(this.accessToken)?.sub ?? null;
  }

  set(tokens: Tokens): void {
    this._tokens.set(tokens);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(tokens));
  }

  /** After a refresh only the access/id tokens rotate; the refresh token stays. */
  updateAccess(accessToken: string, idToken: string): void {
    const current = this._tokens();
    if (!current) return;
    this.set({ ...current, accessToken, idToken });
  }

  clear(): void {
    this._tokens.set(null);
    localStorage.removeItem(STORAGE_KEY);
  }

  private load(): Tokens | null {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as Tokens;
    } catch {
      localStorage.removeItem(STORAGE_KEY);
      return null;
    }
  }
}
