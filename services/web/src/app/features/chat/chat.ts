import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MessagingService } from '../../core/messaging.service';
import { ConversationsService } from '../../core/conversations.service';
import { RealtimeService } from '../../core/realtime.service';
import { NamesService } from '../../core/names.service';
import { errorMessage } from '../../core/http-error';

/**
 * Two-pane chat with directs + groups (Phase 6). Left: conversation list (live,
 * unread badges) plus "new direct" and "new group" starters. Right: the open
 * conversation. Everything is keyed by conversationId; a new direct's id is
 * computed client-side from the peer id.
 */
@Component({
  selector: 'app-chat',
  imports: [FormsModule],
  template: `
    <section class="chat">
      <aside class="list">
        <form class="starter" (ngSubmit)="startDirect()">
          <input type="text" [(ngModel)]="newPeerId" name="peer" placeholder="New chat: user id" />
          <button type="submit" [disabled]="!newPeerId.trim()">+</button>
        </form>
        <button type="button" class="link groupbtn" (click)="showGroup.set(!showGroup())">
          {{ showGroup() ? '× cancel group' : '+ new group' }}
        </button>
        @if (showGroup()) {
          <form class="groupform" (ngSubmit)="startGroup()">
            <input type="text" [(ngModel)]="groupName" name="gname" placeholder="Group name" />
            <input type="text" [(ngModel)]="groupMembers" name="gmembers" placeholder="member ids, comma-separated" />
            <button type="submit" [disabled]="!groupName.trim() || !groupMembers.trim()">Create</button>
          </form>
        }

        <ul>
          @for (c of conversations.conversations(); track c.conversationId) {
            <li [class.active]="c.conversationId === openId()" (click)="open(c.conversationId)">
              <div class="row1">
                <span class="name">{{ c.type === 'group' ? '# ' : '' }}{{ c.title }}</span>
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
        @if (openId()) {
          <div class="header">{{ openTitle() }}</div>
          <ul class="messages">
            @for (m of messaging.messages(); track m.messageId) {
              <li [class.mine]="m.mine">
                <div class="msg">
                  @if (isGroup() && !m.mine) {
                    <span class="sender">{{ names.displayName(m.senderId) }}</span>
                  }
                  <span class="bubble">{{ m.body }}</span>
                </div>
                @if (m.mine && messaging.statusOf(m); as st) {
                  <span class="tick" [class.read]="st === 'read'" [title]="st">
                    {{ st === 'sent' ? '✓' : '✓✓' }}
                  </span>
                }
              </li>
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
      .list { width: 15rem; border-right: 1px solid var(--border); padding-right: 0.75rem; }
      .starter { display: flex; gap: 0.35rem; }
      .starter input { flex: 1; }
      .groupbtn { display: block; margin: 0.4rem 0; font-size: 0.8rem; }
      .groupform { display: flex; flex-direction: column; gap: 0.35rem; margin-bottom: 0.5rem; }
      .list ul { list-style: none; margin: 0.5rem 0 0; padding: 0; display: flex; flex-direction: column; gap: 0.15rem; }
      .list li { padding: 0.45rem 0.5rem; border-radius: 8px; cursor: pointer; }
      .list li:hover { background: var(--bg); }
      .list li.active { background: var(--bg); outline: 1px solid var(--border); }
      .list li.empty { cursor: default; }
      .row1 { display: flex; justify-content: space-between; align-items: center; }
      .name { font-weight: 600; font-size: 0.9rem; }
      .badge { background: var(--accent); color: var(--accent-text); border-radius: 10px; font-size: 0.7rem; padding: 0 0.4rem; }
      .preview { color: var(--muted); font-size: 0.8rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .pane { flex: 1; display: flex; flex-direction: column; position: relative; }
      .header { font-weight: 600; padding-bottom: 0.5rem; border-bottom: 1px solid var(--border); margin-bottom: 0.5rem; }
      .placeholder { margin: auto; }
      .messages { list-style: none; padding: 0; margin: 0 0 auto; display: flex; flex-direction: column; gap: 0.4rem; }
      .messages li { display: flex; align-items: flex-end; gap: 0.25rem; }
      .messages li.mine { justify-content: flex-end; }
      .msg { display: flex; flex-direction: column; gap: 0.1rem; max-width: 75%; }
      .sender { font-size: 0.72rem; font-weight: 600; color: var(--accent); }
      .tick { font-size: 0.7rem; color: var(--muted); }
      .tick.read { color: var(--accent); }
      .bubble { padding: 0.4rem 0.7rem; border-radius: 12px; background: var(--bg); border: 1px solid var(--border); align-self: flex-start; }
      li.mine .msg { align-items: flex-end; }
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
  protected readonly names = inject(NamesService);

  protected newPeerId = '';
  protected groupName = '';
  protected groupMembers = '';
  protected draft = '';
  protected readonly showGroup = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly openId = this.messaging.conversationId;
  protected readonly isGroup = computed(() => this.openId()?.startsWith('grp#') ?? false);

  protected readonly openTitle = computed(() => {
    const id = this.openId();
    return this.conversations.conversations().find((c) => c.conversationId === id)?.title ?? 'Chat';
  });

  async open(conversationId: string): Promise<void> {
    this.error.set(null);
    try {
      await this.messaging.open(conversationId);
      this.conversations.markRead(conversationId);
    } catch (err) {
      this.error.set(errorMessage(err));
    }
  }

  async startDirect(): Promise<void> {
    const peer = this.newPeerId.trim();
    this.newPeerId = '';
    if (!peer) return;
    await this.open(this.messaging.directIdWith(peer));
  }

  async startGroup(): Promise<void> {
    const name = this.groupName.trim();
    const members = this.groupMembers.split(',').map((m) => m.trim()).filter(Boolean);
    if (!name || members.length === 0) return;
    try {
      const conversationId = await this.conversations.createGroup(name, members);
      this.groupName = '';
      this.groupMembers = '';
      this.showGroup.set(false);
      await this.open(conversationId);
    } catch (err) {
      this.error.set(errorMessage(err));
    }
  }

  async send(): Promise<void> {
    const body = this.draft;
    this.draft = '';
    try {
      const sent = await this.messaging.send(body);
      if (sent) this.conversations.recordOutgoing(sent.conversationId, sent.body, sent.sentAt);
    } catch (err) {
      this.error.set(errorMessage(err));
      this.draft = body;
    }
  }
}
