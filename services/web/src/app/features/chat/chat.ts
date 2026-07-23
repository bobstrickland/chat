import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MessagingService } from '../../core/messaging.service';
import { RealtimeService } from '../../core/realtime.service';
import { errorMessage } from '../../core/http-error';

/**
 * Minimal single-conversation view (Phase 4): pick a peer by userId, load
 * history, send, and receive in real time over the shared WebSocket. Enough to
 * run the two-browser delivery test. A real contact list replaces the peer-id
 * box in Phase 6 (groups/contacts).
 */
@Component({
  selector: 'app-chat',
  imports: [FormsModule],
  template: `
    <section class="card">
      <h1>Chat</h1>

      <div class="peer">
        <label>
          Peer user id
          <input type="text" [(ngModel)]="peerId" placeholder="the other user's id" />
        </label>
        <button type="button" (click)="open()" [disabled]="!peerId.trim() || loading()">
          {{ loading() ? 'Opening…' : 'Open' }}
        </button>
        <span class="dot" [class.on]="realtime.connectionState() === 'online'"
              title="live connection"></span>
      </div>

      @if (error()) { <p class="err">{{ error() }}</p> }

      @if (messaging.conversationId()) {
        <ul class="messages">
          @for (m of messaging.messages(); track m.messageId) {
            <li [class.mine]="m.mine">
              <span class="bubble">{{ m.body }}</span>
            </li>
          } @empty {
            <li class="muted">No messages yet — say hello.</li>
          }
        </ul>

        <form class="composer" (ngSubmit)="send()">
          <input type="text" [(ngModel)]="draft" name="draft" placeholder="Message…"
                 autocomplete="off" />
          <button type="submit" [disabled]="!draft.trim()">Send</button>
        </form>
      }
    </section>
  `,
  styles: [
    `
      .peer { display: flex; align-items: flex-end; gap: 0.5rem; }
      .peer label { flex: 1; }
      .dot { width: 0.6rem; height: 0.6rem; border-radius: 50%; background: var(--muted); }
      .dot.on { background: var(--ok); }
      .messages { list-style: none; padding: 0; margin: 1.25rem 0; display: flex; flex-direction: column; gap: 0.4rem; }
      .messages li { display: flex; }
      .messages li.mine { justify-content: flex-end; }
      .bubble { padding: 0.4rem 0.7rem; border-radius: 12px; background: var(--bg); border: 1px solid var(--border); max-width: 75%; }
      li.mine .bubble { background: var(--accent); color: var(--accent-text); border-color: transparent; }
      .muted { color: var(--muted); }
      .composer { display: flex; gap: 0.5rem; }
      .composer input { flex: 1; }
    `,
  ],
})
export class ChatComponent {
  protected readonly messaging = inject(MessagingService);
  protected readonly realtime = inject(RealtimeService);

  protected peerId = '';
  protected draft = '';
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  async open(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      await this.messaging.open(this.peerId.trim());
    } catch (err) {
      this.error.set(errorMessage(err));
    } finally {
      this.loading.set(false);
    }
  }

  async send(): Promise<void> {
    const body = this.draft;
    this.draft = '';
    try {
      await this.messaging.send(body);
    } catch (err) {
      this.error.set(errorMessage(err));
      this.draft = body; // restore on failure
    }
  }
}
