import { Injectable, effect, inject, signal } from '@angular/core';
import { Observable, Subject, filter } from 'rxjs';
import { TokenStore } from './token-store';

/** Local ws-shim endpoint (stands in for API Gateway WebSocket). */
const WS_URL = 'ws://localhost:8090';

type ConnState = 'offline' | 'connecting' | 'online';

/**
 * Owns the single browser WebSocket to the backend (via ws-shim). One socket
 * carries everything the server pushes: presence is implicit in being connected,
 * and message delivery arrives as `{type:"message", ...}` frames. Features
 * subscribe to the frame types they care about via `on(type)`.
 *
 * The socket opens/closes reactively off auth state (an `effect`): sign in →
 * connect, sign out → tear down.
 */
@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private readonly tokenStore = inject(TokenStore);

  readonly connectionState = signal<ConnState>('offline');

  private socket: WebSocket | null = null;
  private closingIntentionally = false;
  private readonly incoming$ = new Subject<Record<string, unknown>>();

  constructor() {
    effect(() => {
      if (this.tokenStore.isAuthenticated()) this.connect();
      else this.disconnect();
    });
  }

  /** Stream of pushed frames of a given `type` (e.g. "message"). */
  on(type: string): Observable<Record<string, unknown>> {
    return this.incoming$.pipe(filter((f) => f['type'] === type));
  }

  private connect(): void {
    if (this.socket) return;
    const token = this.tokenStore.accessToken;
    if (!token) return;

    this.closingIntentionally = false;
    this.connectionState.set('connecting');
    // Token rides in the query string — browsers can't set WS headers.
    const socket = new WebSocket(`${WS_URL}?token=${encodeURIComponent(token)}&device=web`);
    this.socket = socket;

    socket.onopen = () => this.connectionState.set('online');
    socket.onmessage = (e) => {
      try {
        const frame = JSON.parse(e.data);
        if (frame && typeof frame.type === 'string') this.incoming$.next(frame);
      } catch {
        /* ignore non-JSON frames */
      }
    };
    socket.onclose = () => {
      this.connectionState.set('offline');
      this.socket = null;
      // Reconnect once shortly unless this was a sign-out. Minimal for now — a
      // stale (expired) token would be rejected on retry; re-auth-on-reconnect
      // is a later hardening item.
      if (!this.closingIntentionally && this.tokenStore.isAuthenticated()) {
        setTimeout(() => this.connect(), 3000);
      }
    };
    socket.onerror = () => socket.close();
  }

  private disconnect(): void {
    this.closingIntentionally = true;
    this.socket?.close();
    this.socket = null;
    this.connectionState.set('offline');
  }
}
