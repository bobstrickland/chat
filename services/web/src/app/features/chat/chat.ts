import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MessagingService } from '../../core/messaging.service';
import { ConversationsService } from '../../core/conversations.service';
import { RealtimeService } from '../../core/realtime.service';
import { errorMessage } from '../../core/http-error';

/**
 * Two-pane chat (Phase 4 + conversation list). Left: your conversations, live —
 * a message from anyone shows up here with an unread count even if their chat
 * isn't open. Right: the open conversation. A "new chat" box lets you start one
 * by user id (a real contact picker is Phase 6).
 */
@Component({
  selector: 'app-chat',
  imports: [FormsModule],
  template: `
    <section class="chat">
      <aside class="list">
        <form class="newchat" (ngSubmit)="startNew()">
          <input type="text" [(ngModel)]="newPeerId" name="newPeer" placeholder="Start chat: user id" />
          <button type="submit" [disabled]="!newPeerId.trim()">+</button>
        </form>

        <ul>
          @for (c of conversations.conversations(); track c.conversationId) {
            <li [class.active]="c.conversationId === openConversationId()" (click)="open(c.peerId)">
              <div class="row1">
                <span class="name">{{ c.peerName }}</span>
                @if (c.unread > 0) { <span class="badge">{{ c.unread }}</span> }
              </div>
              <div class="preview">{{ c.lastBody ?? '—' }}</div>
            </li>
          } @empty {
            <li class="muted empty">No conversations yet.</li>
          }
        </ul>
      </aside>

      <div class="pane">
        @if (openConversationId()) {
          <ul class="messages">
            @for (m of messaging.messages(); track m.messageId) {
              <li [class.mine]="m.mine"><span class="bubble">{{ m.body }}</span></li>
            } @empty {
              <li class="muted">No messages yet — say hello.</li>
            }
          </ul>
          <form class="composer" (ngSubmit)="send()">
            <input type="text" [(ngModel)]="draft" name="draft" placeholder="Message…" autocomplete="off" />
            <button type="submit" [disabled]="!draft.trim()">Send</button>
          </form>
        } @else {
          <p class="muted placeholder">Select a conversation, or start a new one.</p>
        }
        @if (error()) { <p class="err">{{ error() }}</p> }
        <span class="conn" [class.on]="realtime.connectionState() === 'online'" title="live connection"></span>
      </div>
    </section>
  `,
  styles: [
    `
      .chat { display: flex; gap: 1rem; align-items: stretch; min-height: 24rem; }
      .list { width: 14rem; border-right: 1px solid var(--border); padding-right: 0.75rem; }
      .newchat { display: flex; gap: 0.35rem; margin-bottom: 0.75rem; }
      .newchat input { flex: 1; }
      .list ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.15rem; }
      .list li { padding: 0.45rem 0.5rem; border-radius: 8px; cursor: pointer; }
      .list li:hover { background: var(--bg); }
      .list li.active { background: var(--bg); outline: 1px solid var(--border); }
      .list li.empty { cursor: default; }
      .row1 { display: flex; justify-content: space-between; align-items: center; }
      .name { font-weight: 600; font-size: 0.9rem; }
      .badge { background: var(--accent); color: var(--accent-text); border-radius: 10px; font-size: 0.7rem; padding: 0 0.4rem; }
      .preview { color: var(--muted); font-size: 0.8rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .pane { flex: 1; display: flex; flex-direction: column; position: relative; }
      .placeholder { margin: auto; }
      .messages { list-style: none; padding: 0; margin: 0 0 auto; display: flex; flex-direction: column; gap: 0.4rem; }
      .messages li { display: flex; }
      .messages li.mine { justify-content: flex-end; }
      .bubble { padding: 0.4rem 0.7rem; border-radius: 12px; background: var(--bg); border: 1px solid var(--border); max-width: 75%; }
      li.mine .bubble { background: var(--accent); color: var(--accent-text); border-color: transparent; }
      .composer { display: flex; gap: 0.5rem; margin-top: 0.75rem; }
      .composer input { flex: 1; }
      .muted { color: var(--muted); }
      .conn { position: absolute; top: 0; right: 0; width: 0.55rem; height: 0.55rem; border-radius: 50%; background: var(--muted); }
      .conn.on { background: var(--ok); }
    `,
  ],
})
export class ChatComponent {
  protected readonly messaging = inject(MessagingService);
  protected readonly conversations = inject(ConversationsService);
  protected readonly realtime = inject(RealtimeService);

  protected newPeerId = '';
  protected draft = '';
  protected readonly error = signal<string | null>(null);
  protected readonly openConversationId = this.messaging.conversationId;

  async open(peerId: string): Promise<void> {
    this.error.set(null);
    try {
      await this.messaging.open(peerId);
      const id = this.messaging.conversationId();
      if (id) this.conversations.markRead(id);
    } catch (err) {
      this.error.set(errorMessage(err));
    }
  }

  async startNew(): Promise<void> {
    const peer = this.newPeerId.trim();
    this.newPeerId = '';
    await this.open(peer);
  }

  async send(): Promise<void> {
    const body = this.draft;
    this.draft = '';
    try {
      const sent = await this.messaging.send(body);
      const peer = this.messaging.currentPeerId();
      if (sent && peer) {
        this.conversations.recordOutgoing(sent.conversationId, peer, sent.body, sent.sentAt);
      }
    } catch (err) {
      this.error.set(errorMessage(err));
      this.draft = body;
    }
  }
}
