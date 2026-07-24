import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { TokenStore } from './token-store';
import { RealtimeService } from './realtime.service';

export interface ChatMessage {
  messageId: string;
  conversationId: string;
  senderId: string;
  body: string;
  sentAt: string;
  mediaId: string | null;
  mine: boolean;
}

export type MessageStatus = 'sent' | 'delivered' | 'read' | null;

interface Receipt {
  userId: string;
  kind: 'delivered' | 'read';
  position: string;
}

interface HistoryResponse {
  conversationId: string;
  messages: Omit<ChatMessage, 'mine'>[];
  receipts: Receipt[];
}

/**
 * Drives the ONE open conversation (direct or group), keyed by conversationId.
 * Also tracks the PEER's read/delivered positions so it can render ticks on my
 * messages (direct conversations only — per-member ticks for groups are out of
 * scope), and auto-sends a read receipt while I'm viewing.
 */
@Injectable({ providedIn: 'root' })
export class MessagingService {
  private readonly http = inject(HttpClient);
  private readonly tokenStore = inject(TokenStore);
  private readonly realtime = inject(RealtimeService);

  readonly messages = signal<ChatMessage[]>([]);
  readonly conversationId = signal<string | null>(null);

  // The other participant's furthest read/delivered position (ISO sentAt).
  private readonly peerRead = signal<string | null>(null);
  private readonly peerDelivered = signal<string | null>(null);

  constructor() {
    this.realtime.on('message').subscribe((frame) => {
      if (frame['conversationId'] !== this.conversationId()) return;
      const mine = this.append({
        messageId: String(frame['messageId']),
        conversationId: String(frame['conversationId']),
        senderId: String(frame['senderId']),
        body: String(frame['body']),
        sentAt: String(frame['sentAt']),
        mediaId: (frame['mediaId'] as string | null) ?? null,
      });
      // A new message I can see is a message I've read.
      if (!mine) this.markViewedRead();
    });

    this.realtime.on('receipt').subscribe((frame) => {
      if (frame['conversationId'] !== this.conversationId()) return;
      if (String(frame['userId']) === this.tokenStore.userId) return; // my own receipt
      this.applyPeerReceipt(String(frame['kind']), String(frame['position']));
    });
  }

  directIdWith(peerId: string): string {
    const me = this.tokenStore.userId ?? '';
    const [lo, hi] = me <= peerId ? [me, peerId] : [peerId, me];
    return `dm#${lo}#${hi}`;
  }

  async open(conversationId: string): Promise<void> {
    this.messages.set([]);
    this.peerRead.set(null);
    this.peerDelivered.set(null);
    this.conversationId.set(conversationId);

    const res = await firstValueFrom(
      this.http.get<HistoryResponse>(`/conversations/${encodeURIComponent(conversationId)}/messages`),
    );
    if (this.conversationId() !== conversationId) return; // switched away mid-load
    this.messages.set(res.messages.map((m) => this.decorate(m)));
    for (const r of res.receipts ?? []) {
      if (r.userId !== this.tokenStore.userId) this.applyPeerReceipt(r.kind, r.position);
    }
    this.markViewedRead();
  }

  async send(body: string, mediaId?: string): Promise<(Omit<ChatMessage, 'mine'>) | null> {
    const conversationId = this.conversationId();
    if (!conversationId || (!body.trim() && !mediaId)) return null;
    const sent = await firstValueFrom(
      this.http.post<Omit<ChatMessage, 'mine'>>(
        `/conversations/${encodeURIComponent(conversationId)}/messages`,
        { body: body.trim(), mediaId: mediaId ?? null },
      ),
    );
    this.append(sent);
    return sent;
  }

  /** Read/delivered/sent status for one of MY messages (direct convos only). */
  statusOf(m: ChatMessage): MessageStatus {
    if (!m.mine) return null;
    const id = this.conversationId();
    if (!id || id.startsWith('grp#')) return null; // no per-message ticks for groups
    const read = this.peerRead();
    if (read && read >= m.sentAt) return 'read';
    const delivered = this.peerDelivered();
    if (delivered && delivered >= m.sentAt) return 'delivered';
    return 'sent';
  }

  /** @returns true if the appended message was my own. */
  private append(m: Omit<ChatMessage, 'mine'>): boolean {
    const decorated = this.decorate(m);
    if (this.messages().some((x) => x.messageId === m.messageId)) return decorated.mine;
    this.messages.update((list) => [...list, decorated]);
    return decorated.mine;
  }

  /** Advance a peer position forward-only. */
  private applyPeerReceipt(kind: string, position: string): void {
    const target = kind === 'read' ? this.peerRead : kind === 'delivered' ? this.peerDelivered : null;
    if (!target) return;
    const prev = target();
    if (!prev || position > prev) target.set(position);
  }

  /** Tell the server I've read up to the latest message (fire-and-forget). */
  private markViewedRead(): void {
    const id = this.conversationId();
    const list = this.messages();
    if (!id || list.length === 0) return;
    const latest = list[list.length - 1].sentAt;
    this.http
      .post(`/conversations/${encodeURIComponent(id)}/receipts`, { kind: 'read', position: latest })
      .subscribe({ error: () => {} });
  }

  private decorate(m: Omit<ChatMessage, 'mine'>): ChatMessage {
    return { ...m, mine: m.senderId === this.tokenStore.userId };
  }
}
