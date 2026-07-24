import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, firstValueFrom, of } from 'rxjs';
import { TokenStore } from './token-store';
import { RealtimeService } from './realtime.service';
import { MessagingService } from './messaging.service';

export interface ConversationRow {
  conversationId: string;
  type: 'direct' | 'group';
  title: string; // group name, or the peer's display name (enriched)
  peerId: string | null;
  lastBody: string | null;
  lastSentAt: string | null;
  unread: number;
}

interface ListResponse {
  conversations: {
    conversationId: string;
    type: 'direct' | 'group';
    name: string | null;
    peerId: string | null;
    lastMessage: { body: string; senderId: string; sentAt: string } | null;
  }[];
}

/**
 * The always-on inbox: the conversation list + unread counts, maintained for the
 * whole session off the shared socket. Handles both directs and groups. A
 * message for a conversation you're not viewing (or a group you were just added
 * to) still surfaces here. Client-side unread only; read-state is Phase 7.
 */
@Injectable({ providedIn: 'root' })
export class ConversationsService {
  private readonly http = inject(HttpClient);
  private readonly tokenStore = inject(TokenStore);
  private readonly realtime = inject(RealtimeService);
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

    this.realtime.on('message').subscribe((frame) => {
      const conversationId = String(frame['conversationId']);
      const senderId = String(frame['senderId']); // for a direct, this is the peer
      const sentAt = String(frame['sentAt']);
      const mine = senderId === this.tokenStore.userId; // a multi-device echo of my own message

      // The frame carries no content type, so an incoming media preview is generic.
      const preview = String(frame['body'] || '') || (frame['mediaId'] ? '📎 Attachment' : '');
      this.recordActivity({
        conversationId,
        directPeerId: senderId,
        body: preview,
        sentAt,
        // Don't count the conversation I'm viewing, nor my own echoes, as unread.
        incrementUnread: conversationId !== this.messaging.conversationId() && !mine,
      });

      // Acknowledge delivery of someone else's message (drives the sender's tick).
      if (!mine) {
        this.http
          .post(`/conversations/${encodeURIComponent(conversationId)}/receipts`, {
            kind: 'delivered',
            position: sentAt,
          })
          .subscribe({ error: () => {} });
      }
    });

    // Multi-device read sync: when ANOTHER of my devices reads a conversation,
    // clear its unread here too.
    this.realtime.on('receipt').subscribe((frame) => {
      if (String(frame['userId']) === this.tokenStore.userId && frame['kind'] === 'read') {
        this.markRead(String(frame['conversationId']));
      }
    });
  }

  /** Fetch the list, MERGING to preserve existing unread counts. */
  load(): void {
    this.http
      .get<ListResponse>('/conversations')
      .pipe(catchError(() => of<ListResponse>({ conversations: [] })))
      .subscribe((res) => {
        const prev = new Map(this.conversations().map((c) => [c.conversationId, c]));
        this.conversations.set(
          res.conversations.map((c) => {
            const existing = prev.get(c.conversationId);
            return {
              conversationId: c.conversationId,
              type: c.type,
              title:
                c.type === 'group'
                  ? c.name ?? 'Group'
                  : this.peerNameCache.get(c.peerId!) ?? existing?.title ?? shortId(c.peerId!),
              peerId: c.peerId,
              lastBody: c.lastMessage?.body ?? null,
              lastSentAt: c.lastMessage?.sentAt ?? null,
              unread: existing?.unread ?? 0,
            };
          }),
        );
        this.resort();
        for (const c of res.conversations) {
          if (c.type === 'direct' && c.peerId) this.enrichPeerName(c.peerId);
        }
      });
  }

  /** Create a group, then refresh; returns the new conversationId. */
  async createGroup(name: string, memberIds: string[]): Promise<string> {
    const res = await firstValueFrom(
      this.http.post<{ conversationId: string }>('/conversations', { name, memberIds }),
    );
    this.load();
    return res.conversationId;
  }

  /** Call after the user sends — delivery excludes the sender, so we self-report. */
  recordOutgoing(conversationId: string, body: string, sentAt: string): void {
    this.recordActivity({
      conversationId,
      directPeerId: this.directPeer(conversationId),
      body,
      sentAt,
      incrementUnread: false,
    });
  }

  markRead(conversationId: string): void {
    this.conversations.update((list) =>
      list.map((c) => (c.conversationId === conversationId ? { ...c, unread: 0 } : c)),
    );
  }

  private recordActivity(input: {
    conversationId: string;
    directPeerId: string | null;
    body: string;
    sentAt: string;
    incrementUnread: boolean;
  }): void {
    const existing = this.conversations().find((c) => c.conversationId === input.conversationId);
    if (existing) {
      this.conversations.update((list) =>
        list.map((c) =>
          c.conversationId === input.conversationId
            ? {
                ...c,
                lastBody: input.body,
                lastSentAt: input.sentAt,
                unread: input.incrementUnread ? c.unread + 1 : c.unread,
              }
            : c,
        ),
      );
      this.resort();
      return;
    }

    // Unknown conversation (new group, or a first direct). Add a provisional row
    // immediately, then load() to fill in the real name/type (merge preserves
    // this row's unread).
    const isGroup = input.conversationId.startsWith('grp#');
    const peerId = isGroup ? null : input.directPeerId;
    this.conversations.update((list) => [
      ...list,
      {
        conversationId: input.conversationId,
        type: isGroup ? 'group' : 'direct',
        title: isGroup ? 'New group' : this.peerNameCache.get(peerId!) ?? shortId(peerId ?? ''),
        peerId,
        lastBody: input.body,
        lastSentAt: input.sentAt,
        unread: input.incrementUnread ? 1 : 0,
      },
    ]);
    this.resort();
    if (peerId) this.enrichPeerName(peerId);
    this.load();
  }

  /** The other participant in a direct id, from this user's perspective. */
  private directPeer(conversationId: string): string | null {
    if (!conversationId.startsWith('dm#')) return null;
    const parts = conversationId.split('#'); // ['dm', a, b]
    const me = this.tokenStore.userId ?? '';
    return parts[1] === me ? parts[2] : parts[1];
  }

  private resort(): void {
    this.conversations.update((list) =>
      [...list].sort((a, b) => (b.lastSentAt ?? '').localeCompare(a.lastSentAt ?? '')),
    );
  }

  private enrichPeerName(peerId: string): void {
    const cached = this.peerNameCache.get(peerId);
    if (cached) {
      this.applyTitle(peerId, cached);
      return;
    }
    this.http
      .get<{ displayName: string }>(`/profiles/${encodeURIComponent(peerId)}`)
      .pipe(catchError(() => of(null)))
      .subscribe((profile) => {
        const name = profile?.displayName || shortId(peerId);
        this.peerNameCache.set(peerId, name);
        this.applyTitle(peerId, name);
      });
  }

  private applyTitle(peerId: string, title: string): void {
    this.conversations.update((list) =>
      list.map((c) => (c.type === 'direct' && c.peerId === peerId ? { ...c, title } : c)),
    );
  }
}

function shortId(id: string): string {
  return id.length > 10 ? id.slice(0, 8) + '…' : id;
}
