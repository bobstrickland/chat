import { Injectable, computed, signal } from '@angular/core';
import { Tokens } from './models';

const STORAGE_KEY = 'chat.tokens';

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

  get accessToken(): string | null {
    return this._tokens()?.accessToken ?? null;
  }

  get refreshToken(): string | null {
    return this._tokens()?.refreshToken ?? null;
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
