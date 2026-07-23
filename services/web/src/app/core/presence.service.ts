import { Injectable, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Subscription, catchError, of, switchMap, timer } from 'rxjs';
import { TokenStore } from './token-store';

/** Local ws-shim endpoint (stands in for API Gateway WebSocket). */
const WS_URL = 'ws://localhost:8090';

/**
 * Hardcoded "contact" whose presence we display — Phase 3 has no contacts/groups
 * yet (that's Phase 6). Set to the dev fixture user's id, so that when you're
 * signed in as that user with a socket open, this panel actually shows "online"
 * — the full loop (your WS registers presence → the status poll observes it).
 */
const DEMO_CONTACT_USER_ID = '74b89438-d021-706b-e56a-179ab2cdc5e0';

type ConnState = 'offline' | 'connecting' | 'online';

/**
 * Owns the browser's WebSocket to Presence (via ws-shim) and a poll of one
 * contact's online status.
 *
 * The socket opens/closes reactively off auth state via an `effect` — sign in
 * and it connects, sign out and it tears down. (An `effect` re-runs whenever a
 * signal it reads changes; here that's `isAuthenticated`.)
 */
@Injectable({ providedIn: 'root' })
export class PresenceService {
  private readonly http = inject(HttpClient);
  private readonly tokenStore = inject(TokenStore);

  readonly connectionState = signal<ConnState>('offline');
  readonly contactId = DEMO_CONTACT_USER_ID;
  readonly contactOnline = signal<boolean | null>(null);

  private socket: WebSocket | null = null;
  private poll?: Subscription;
  private closingIntentionally = false;

  constructor() {
    effect(() => {
      if (this.tokenStore.isAuthenticated()) {
        this.connect();
      } else {
        this.disconnect();
      }
    });
  }

  private connect(): void {
    if (this.socket) return; // already connected/connecting
    const token = this.tokenStore.accessToken;
    if (!token) return;

    this.closingIntentionally = false;
    this.connectionState.set('connecting');

    // The token can't go in a header (browsers can't set WS headers), so it
    // rides in the query string — ws-shim forwards it to Presence's $connect.
    const socket = new WebSocket(`${WS_URL}?token=${encodeURIComponent(token)}&device=web`);
    this.socket = socket;

    socket.onopen = () => this.connectionState.set('online');
    socket.onclose = () => {
      this.connectionState.set('offline');
      this.socket = null;
      // Reconnect once shortly if the drop wasn't a sign-out. Kept minimal for
      // Phase 3 — a stale (expired) access token would be rejected on retry;
      // proper re-auth-on-reconnect is a later hardening item.
      if (!this.closingIntentionally && this.tokenStore.isAuthenticated()) {
        setTimeout(() => this.connect(), 3000);
      }
    };
    socket.onerror = () => socket.close();

    this.startPollingContact();
  }

  private disconnect(): void {
    this.closingIntentionally = true;
    this.poll?.unsubscribe();
    this.poll = undefined;
    this.contactOnline.set(null);
    this.socket?.close();
    this.socket = null;
    this.connectionState.set('offline');
  }

  /** Poll the contact's status every 5s. timer(0, 5000) fires immediately then
   *  on an interval; switchMap swaps each tick for the HTTP call.
   *
   *  catchError lives INSIDE switchMap on purpose: an error there is confined to
   *  that one inner observable and returns a fallback, so the outer timer keeps
   *  ticking. If catchError sat on the outer pipe instead, one failed poll would
   *  terminate the whole stream and polling would stop forever. */
  private startPollingContact(): void {
    this.poll?.unsubscribe();
    this.poll = timer(0, 5000)
      .pipe(
        switchMap(() =>
          this.http
            .get<{ userId: string; online: boolean }>(
              `/presence/status/${encodeURIComponent(this.contactId)}`,
            )
            .pipe(catchError(() => of(null))),
        ),
      )
      .subscribe((res) => {
        if (res) this.contactOnline.set(res.online);
      });
  }
}
