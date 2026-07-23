import { Component, inject } from '@angular/core';
import { PresenceService } from '../../core/presence.service';

/**
 * Small live-status widget (Phase 3): shows this browser's own WebSocket
 * connection state and one hardcoded contact's online/offline, polled from
 * Presence. Grows into a real contact/presence list in later phases.
 */
@Component({
  selector: 'app-presence-panel',
  template: `
    <section class="card presence">
      <h2>Live presence</h2>

      <div class="row">
        <span class="label">This session</span>
        <span class="dot" [class.on]="presence.connectionState() === 'online'"></span>
        <span>{{ presence.connectionState() }}</span>
      </div>

      <div class="row">
        <span class="label">Contact</span>
        @if (presence.contactOnline() === null) {
          <span class="muted">…</span>
        } @else {
          <span class="dot" [class.on]="presence.contactOnline()"></span>
          <span>{{ presence.contactOnline() ? 'online' : 'offline' }}</span>
        }
      </div>
      <p class="hint code">{{ presence.contactId }}</p>
    </section>
  `,
  styles: [
    `
      .presence { margin-top: 1rem; }
      h2 { font-size: 1rem; margin: 0 0 0.75rem; }
      .row { display: flex; align-items: center; gap: 0.5rem; margin: 0.35rem 0; }
      .label { width: 6rem; color: var(--muted); font-size: 0.85rem; }
      .dot {
        width: 0.6rem; height: 0.6rem; border-radius: 50%;
        background: var(--muted); display: inline-block;
      }
      .dot.on { background: var(--ok); }
      .muted { color: var(--muted); }
      .code { font-family: ui-monospace, monospace; font-size: 0.75rem; word-break: break-all; }
    `,
  ],
})
export class PresencePanelComponent {
  protected readonly presence = inject(PresenceService);
}
