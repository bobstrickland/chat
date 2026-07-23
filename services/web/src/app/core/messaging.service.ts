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
  mine: boolean;
}

interface HistoryResponse {
  conversationId: string;
  messages: Omit<ChatMessage, 'mine'>[];
}

/**
 * Drives the single-conversation chat view. History comes over HTTP; new
 * messages from the other side arrive over the shared WebSocket
 * (RealtimeService) as `message` frames. Own sent messages are appended
 * locally (the server excludes the sender from delivery in Phase 4).
 */
@Injectable({ providedIn: 'root' })
export class MessagingService {
  private readonly http = inject(HttpClient);
  private readonly tokenStore = inject(TokenStore);
  private readonly realtime = inject(RealtimeService);

  readonly messages = signal<ChatMessage[]>([]);
  readonly conversationId = signal<string | null>(null);
  private peerId: string | null = null;

  constructor() {
    // One long-lived subscription: append any delivered message that belongs to
    // the conversation currently open.
    this.realtime.on('message').subscribe((frame) => {
      if (frame['conversationId'] !== this.conversationId()) return;
      this.append({
        messageId: String(frame['messageId']),
        conversationId: String(frame['conversationId']),
        senderId: String(frame['senderId']),
        body: String(frame['body']),
        sentAt: String(frame['sentAt']),
      });
    });
  }

  /** Load history for the direct conversation with `peerId` and make it current. */
  async open(peerId: string): Promise<void> {
    this.peerId = peerId;
    this.messages.set([]);
    this.conversationId.set(null);
    const res = await firstValueFrom(
      this.http.get<HistoryResponse>(`/conversations/direct/${encodeURIComponent(peerId)}/messages`),
    );
    this.conversationId.set(res.conversationId);
    this.messages.set(res.messages.map((m) => this.decorate(m)));
  }

  async send(body: string): Promise<void> {
    if (!this.peerId || !body.trim()) return;
    const sent = await firstValueFrom(
      this.http.post<Omit<ChatMessage, 'mine'>>('/messages', {
        recipientId: this.peerId,
        body: body.trim(),
      }),
    );
    // Append our own message locally — delivery skips the sender.
    this.append(sent);
  }

  private append(m: Omit<ChatMessage, 'mine'>): void {
    // Guard against double-append (e.g. a future echo) by messageId.
    if (this.messages().some((x) => x.messageId === m.messageId)) return;
    this.messages.update((list) => [...list, this.decorate(m)]);
  }

  private decorate(m: Omit<ChatMessage, 'mine'>): ChatMessage {
    return { ...m, mine: m.senderId === this.tokenStore.userId };
  }
}
