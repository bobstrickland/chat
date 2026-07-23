import { Injectable, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Subscription, catchError, of, switchMap, timer } from 'rxjs';
import { TokenStore } from './token-store';
import { RealtimeService } from './realtime.service';

/**
 * Hardcoded "contact" whose presence we display — Phase 3 has no contacts/groups
 * yet (that's Phase 6). Set to the dev fixture user's id so that, signed in as
 * that user with a socket open, this shows "online" — demonstrating the loop
 * (the WS registers presence → the status poll observes it).
 */
const DEMO_CONTACT_USER_ID = '74b89438-d021-706b-e56a-179ab2cdc5e0';

/**
 * Presence view-state. The live WebSocket now lives in RealtimeService (one
 * socket, many concerns); this service re-exposes its connection state and adds
 * a poll of one contact's online status.
 */
@Injectable({ providedIn: 'root' })
export class PresenceService {
  private readonly http = inject(HttpClient);
  private readonly tokenStore = inject(TokenStore);
  private readonly realtime = inject(RealtimeService);

  /** This browser's own connection state — delegated to the shared socket. */
  readonly connectionState = this.realtime.connectionState;
  readonly contactId = DEMO_CONTACT_USER_ID;
  readonly contactOnline = signal<boolean | null>(null);

  private poll?: Subscription;

  constructor() {
    // Poll the contact's status only while authenticated.
    effect(() => {
      if (this.tokenStore.isAuthenticated()) this.startPolling();
      else this.stopPolling();
    });
  }

  private startPolling(): void {
    if (this.poll) return;
    // timer(0, 5000): fire now, then every 5s. catchError INSIDE switchMap so a
    // failed poll returns a fallback and the timer keeps ticking (a failure on
    // the outer pipe would terminate the stream permanently).
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

  private stopPolling(): void {
    this.poll?.unsubscribe();
    this.poll = undefined;
    this.contactOnline.set(null);
  }
}
