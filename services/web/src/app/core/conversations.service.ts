import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, of } from 'rxjs';
import { TokenStore } from './token-store';
import { RealtimeService } from './realtime.service';
import { MessagingService } from './messaging.service';

export interface ConversationRow {
  conversationId: string;
  peerId: string;
  peerName: string; // enriched from Profile; falls back to a short id
  lastBody: string | null;
  lastSentAt: string | null;
  unread: number;
}

interface ListResponse {
  conversations: {
    conversationId: string;
    peerId: string;
    lastMessage: { body: string; senderId: string; sentAt: string } | null;
  }[];
}

/**
 * The always-on inbox. Unlike MessagingService (which tracks the ONE open
 * conversation), this listens to the socket for the whole session and maintains
 * the conversation list + unread counts — so a message from someone whose chat
 * you aren't viewing still shows up, instead of being dropped.
 *
 * Client-side unread only (resets on reload); true read-state is Phase 7.
 */
@Injectable({ providedIn: 'root' })
export class ConversationsService {
  private readonly http = inject(HttpClient);
  private readonly tokenStore = inject(TokenStore);
  private readonly realtime = inject(RealtimeService);
  // Read-only use of MessagingService (its conversationId signal) — no back-
  // injection there, so no DI cycle.
  private readonly messaging = inject(MessagingService);

  readonly conversations = signal<ConversationRow[]>([]);
  readonly totalUnread = computed(() =>
    this.conversations().reduce((sum, c) => sum + c.unread, 0),
  );

  private readonly peerNameCache = new Map<string, string>();

  constructor() {
    effect(() => {
      if (this.tokenStore.isAuthenticated()) this.load();
      else this.conversations.set([]);
    });

    // Session-long: every delivered message updates the list, whether or not
    // its conversation is currently open.
    this.realtime.on('message').subscribe((frame) => {
      const conversationId = String(frame['conversationId']);
      const senderId = String(frame['senderId']); // for an incoming direct msg, the peer
      this.upsert({
        conversationId,
        peerId: senderId,
        body: String(frame['body']),
        sentAt: String(frame['sentAt']),
        // Don't count unread for the conversation you're looking at.
        incrementUnread: conversationId !== this.messaging.conversationId(),
      });
    });
  }

  private load(): void {
    this.http
      .get<ListResponse>('/conversations')
      .pipe(catchError(() => of<ListResponse>({ conversations: [] })))
      .subscribe((res) => {
        this.conversations.set(
          res.conversations.map((c) => ({
            conversationId: c.conversationId,
            peerId: c.peerId,
            peerName: this.peerNameCache.get(c.peerId) ?? shortId(c.peerId),
            lastBody: c.lastMessage?.body ?? null,
            lastSentAt: c.lastMessage?.sentAt ?? null,
            unread: 0,
          })),
        );
        this.resort();
        for (const c of res.conversations) this.enrichPeerName(c.peerId);
      });
  }

  /** Call when the user sends — delivery excludes the sender, so we self-report. */
  recordOutgoing(conversationId: string, peerId: string, body: string, sentAt: string): void {
    this.upsert({ conversationId, peerId, body, sentAt, incrementUnread: false });
  }

  markRead(conversationId: string): void {
    this.conversations.update((list) =>
      list.map((c) => (c.conversationId === conversationId ? { ...c, unread: 0 } : c)),
    );
  }

  private upsert(input: {
    conversationId: string;
    peerId: string;
    body: string;
    sentAt: string;
    incrementUnread: boolean;
  }): void {
    this.conversations.update((list) => {
      const existing = list.find((c) => c.conversationId === input.conversationId);
      if (existing) {
        return list.map((c) =>
          c.conversationId === input.conversationId
            ? {
                ...c,
                lastBody: input.body,
                lastSentAt: input.sentAt,
                unread: input.incrementUnread ? c.unread + 1 : c.unread,
              }
            : c,
        );
      }
      return [
        ...list,
        {
          conversationId: input.conversationId,
          peerId: input.peerId,
          peerName: this.peerNameCache.get(input.peerId) ?? shortId(input.peerId),
          lastBody: input.body,
          lastSentAt: input.sentAt,
          unread: input.incrementUnread ? 1 : 0,
        },
      ];
    });
    this.resort();
    this.enrichPeerName(input.peerId);
  }

  /** Newest activity first. */
  private resort(): void {
    this.conversations.update((list) =>
      [...list].sort((a, b) => (b.lastSentAt ?? '').localeCompare(a.lastSentAt ?? '')),
    );
  }

  /** Fetch the peer's display name (any authed user may read a profile). */
  private enrichPeerName(peerId: string): void {
    if (this.peerNameCache.has(peerId)) {
      this.applyPeerName(peerId, this.peerNameCache.get(peerId)!);
      return;
    }
    this.http
      .get<{ displayName: string }>(`/profiles/${encodeURIComponent(peerId)}`)
      .pipe(catchError(() => of(null)))
      .subscribe((profile) => {
        const name = profile?.displayName || shortId(peerId);
        this.peerNameCache.set(peerId, name);
        this.applyPeerName(peerId, name);
      });
  }

  private applyPeerName(peerId: string, name: string): void {
    this.conversations.update((list) =>
      list.map((c) => (c.peerId === peerId ? { ...c, peerName: name } : c)),
    );
  }
}

function shortId(id: string): string {
  return id.length > 10 ? id.slice(0, 8) + '…' : id;
}
