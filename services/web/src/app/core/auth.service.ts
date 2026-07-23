import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, finalize, map, shareReplay, tap } from 'rxjs';
import { TokenStore } from './token-store';
import { AUTH_CONFIG, oauthRedirectUri } from './auth-config';
import {
  LoginResult,
  MfaEnrollResult,
  MfaVerifyResult,
  RefreshResult,
  RegisterResult,
  Tokens,
} from './models';

/**
 * Talks to the Auth service (proxied at /auth). Every method returns an
 * Observable — the RxJS equivalent of an RxJava `Single`: cold (nothing runs
 * until subscribed) and emitting once. Angular's HttpClient subscribes when
 * you do, and the `async` pipe / component subscription drives it.
 *
 * `tap(...)` here is exactly RxJava's `doOnNext` — a side effect (persisting
 * tokens) that doesn't alter the stream.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly tokenStore = inject(TokenStore);

  register(email: string, password: string): Observable<RegisterResult> {
    return this.http.post<RegisterResult>('/auth/register', { email, password });
  }

  /**
   * On a full (non-MFA) login the tokens come back immediately, so persist
   * them. On an MFA challenge they're null — persistence waits for verifyMfa.
   */
  login(email: string, password: string): Observable<LoginResult> {
    return this.http.post<LoginResult>('/auth/login', { email, password }).pipe(
      tap((res) => {
        if (!res.mfaRequired && res.accessToken) {
          this.tokenStore.set(res as Tokens);
        }
      }),
    );
  }

  /** Login-challenge branch: has a session, no access token yet. */
  verifyMfaChallenge(email: string, mfaSession: string, code: string): Observable<MfaVerifyResult> {
    return this.http
      .post<MfaVerifyResult>('/auth/mfa/verify', { email, mfaSession, code })
      .pipe(
        tap((res) => {
          if (res.verified && res.accessToken) {
            this.tokenStore.set(res as Tokens);
          }
        }),
      );
  }

  enrollMfa(): Observable<MfaEnrollResult> {
    // accessToken is attached by the interceptor; the body echoes it because
    // the Auth core validates it from the payload, not the header.
    return this.http.post<MfaEnrollResult>('/auth/mfa/enroll', {
      accessToken: this.tokenStore.accessToken,
    });
  }

  /** Post-enrollment branch: has an access token, no session. */
  confirmMfaEnrollment(code: string): Observable<MfaVerifyResult> {
    return this.http.post<MfaVerifyResult>('/auth/mfa/verify', {
      accessToken: this.tokenStore.accessToken,
      code,
    });
  }

  /**
   * Exchanges the refresh token for a fresh access/id pair. Used by the
   * interceptor on a 401. Cognito rotates only access+id here, not the refresh
   * token, so TokenStore.updateAccess keeps the existing refresh token.
   */
  refresh(): Observable<RefreshResult> {
    return this.http
      .post<RefreshResult>('/auth/refresh', { refreshToken: this.tokenStore.refreshToken })
      .pipe(tap((res) => this.tokenStore.updateAccess(res.accessToken, res.idToken)));
  }

  logout(): void {
    this.tokenStore.clear();
  }

  // --- federated (Google via Cognito Hosted UI) -----------------------------

  /**
   * Kick off Google sign-in: full-page redirect to the Cognito Hosted UI, which
   * brokers the Google OAuth and redirects back to `oauthRedirectUri()` with a
   * `?code`. Not an XHR — the browser leaves the app and returns to /auth/callback.
   */
  startFederatedLogin(provider: 'google'): void {
    const params = new URLSearchParams({
      identity_provider: provider === 'google' ? 'Google' : provider,
      redirect_uri: oauthRedirectUri(),
      response_type: 'code',
      client_id: AUTH_CONFIG.webClientId,
      scope: AUTH_CONFIG.scopes.join(' '),
    });
    window.location.href = `${AUTH_CONFIG.hostedUiDomain}/oauth2/authorize?${params}`;
  }

  /**
   * Exchange the returned auth code for tokens via the Auth service (which does
   * the server-side call to Cognito's /oauth2/token). The redirectUri must match
   * the one used to start the flow, exactly.
   */
  completeFederatedLogin(provider: 'google', code: string): Observable<Tokens> {
    return this.http
      .post<Tokens>('/auth/federated', { provider, code, redirectUri: oauthRedirectUri() })
      .pipe(tap((res) => this.tokenStore.set(res)));
  }

  // --- single-flight refresh ------------------------------------------------
  // If several protected requests 401 at once, they must share ONE refresh
  // call, not stampede the endpoint. This is the canonical RxJS pattern:
  // `shareReplay` multicasts a single underlying subscription to all callers
  // (like RxJava's `.share()`/`replay(1).refCount()`), and `finalize` clears
  // the cached observable once everyone has detached, so the *next* 401 starts
  // a fresh refresh instead of replaying a stale token.
  private refreshInFlight$: Observable<string> | null = null;

  refreshShared(): Observable<string> {
    if (!this.refreshInFlight$) {
      this.refreshInFlight$ = this.refresh().pipe(
        map((res) => res.accessToken),
        shareReplay({ bufferSize: 1, refCount: true }),
        finalize(() => {
          this.refreshInFlight$ = null;
        }),
      );
    }
    return this.refreshInFlight$;
  }
}
