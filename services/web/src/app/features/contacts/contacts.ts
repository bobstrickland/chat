import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContactsService } from '../../core/contacts.service';
import { MediaService } from '../../core/media.service';

/**
 * The signed-in user's contacts (Phase 11). Each row links to that user's
 * profile (/u/:userId) and offers a quick remove. The list itself lives in
 * ContactsService (loaded on login), so it's already warm when this opens.
 */
@Component({
  selector: 'app-contacts',
  imports: [RouterLink],
  template: `
    <section class="card">
      <h1>Contacts</h1>
      <ul class="list">
        @for (c of contacts.contacts(); track c.userId) {
          <li>
            <a class="who" [routerLink]="['/u', c.userId]">
              <span class="avatar">
                @if (c.avatarMediaId && media.ready(c.avatarMediaId)) {
                  <img [src]="media.displayUrl(c.avatarMediaId)" alt="" />
                } @else {
                  {{ initials(c.displayName) }}
                }
              </span>
              <span class="name">{{ c.displayName || c.userId }}</span>
            </a>
            <button type="button" class="link" (click)="remove(c.userId)">Remove</button>
          </li>
        } @empty {
          <li class="muted empty">
            No contacts yet. Add people from <a routerLink="/search">Search</a>, or double-click a
            chat.
          </li>
        }
      </ul>
    </section>
  `,
  styles: [
    `
      .list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 0.25rem; }
      .list li { display: flex; align-items: center; justify-content: space-between; padding: 0.4rem 0.5rem; border-radius: 8px; }
      .list li:hover { background: var(--bg); }
      .who { display: flex; align-items: center; gap: 0.6rem; text-decoration: none; color: inherit; flex: 1; }
      .avatar { width: 36px; height: 36px; border-radius: 50%; overflow: hidden; background: var(--bg); border: 1px solid var(--border); display: flex; align-items: center; justify-content: center; font-size: 0.75rem; color: var(--muted); flex: none; }
      .avatar img { width: 100%; height: 100%; object-fit: cover; }
      .name { font-weight: 600; }
      .empty { cursor: default; }
      .muted { color: var(--muted); }
    `,
  ],
})
export class ContactsComponent {
  protected readonly contacts = inject(ContactsService);
  protected readonly media = inject(MediaService);

  async remove(userId: string): Promise<void> {
    try {
      await this.contacts.remove(userId);
    } catch {
      /* keep the row; a transient failure will resolve on next load */
    }
  }

  initials(name: string | null): string {
    return (name ?? '?').trim().slice(0, 2).toUpperCase() || '?';
  }
}
