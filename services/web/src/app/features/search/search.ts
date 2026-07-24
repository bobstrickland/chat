import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap, catchError, of } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SearchService, SearchResults } from '../../core/search.service';
import { MessagingService } from '../../core/messaging.service';
import { NamesService } from '../../core/names.service';

/**
 * Global search over messages (your conversations only) and people. The box is
 * debounced — it fires as you type, not on submit. A hit opens the relevant
 * conversation and jumps to the chat view: a message hit opens its conversation,
 * a person hit opens (or starts) a direct with them.
 */
@Component({
  selector: 'app-search',
  imports: [FormsModule],
  template: `
    <section class="search">
      <h2>Search</h2>
      <input
        type="text"
        [(ngModel)]="q"
        (ngModelChange)="queries.next($event)"
        placeholder="Search messages and people…"
        autocomplete="off"
        autofocus
      />

      @if (loading()) { <p class="muted">Searching…</p> }
      @if (error()) { <p class="err">{{ error() }}</p> }

      @if (results(); as r) {
        @if (r.users.length) {
          <h3>People</h3>
          <ul class="results">
            @for (u of r.users; track u.userId) {
              <li (click)="openUser(u.userId)">
                <span class="name">{{ u.displayName || u.userId }}</span>
                @if (u.bio) { <span class="snippet">{{ u.bio }}</span> }
              </li>
            }
          </ul>
        }

        @if (r.messages.length) {
          <h3>Messages</h3>
          <ul class="results">
            @for (m of r.messages; track m.messageId) {
              <li (click)="openMessage(m.conversationId)">
                <span class="name">{{ names.displayName(m.senderId) }}</span>
                <span class="snippet">{{ m.body }}</span>
              </li>
            }
          </ul>
        }

        @if (!r.users.length && !r.messages.length && q.trim() && !loading()) {
          <p class="muted">No matches for “{{ q }}”.</p>
        }
      }
    </section>
  `,
  styles: [
    `
      .search { max-width: 34rem; }
      .search input { width: 100%; padding: 0.55rem 0.7rem; font-size: 1rem; }
      h3 { margin: 1rem 0 0.35rem; font-size: 0.8rem; text-transform: uppercase; color: var(--muted); }
      .results { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 0.15rem; }
      .results li { padding: 0.5rem 0.6rem; border-radius: 8px; cursor: pointer; display: flex; flex-direction: column; gap: 0.1rem; }
      .results li:hover { background: var(--bg); }
      .name { font-weight: 600; font-size: 0.9rem; }
      .snippet { color: var(--muted); font-size: 0.85rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .muted { color: var(--muted); }
      .err { color: var(--err, #c0392b); }
    `,
  ],
})
export class SearchComponent {
  private readonly searchService = inject(SearchService);
  private readonly messaging = inject(MessagingService);
  private readonly router = inject(Router);
  protected readonly names = inject(NamesService);

  protected q = '';
  protected readonly queries = new Subject<string>();
  protected readonly results = signal<SearchResults | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.queries
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) => {
          const query = q.trim();
          if (!query) {
            this.loading.set(false);
            this.results.set(null);
            return of<SearchResults | null>(null);
          }
          this.loading.set(true);
          this.error.set(null);
          return this.searchService.search(query).pipe(
            catchError(() => {
              this.error.set('Search failed.');
              return of<SearchResults | null>(null);
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((r) => {
        this.loading.set(false);
        if (r) this.results.set(r);
      });
  }

  async openMessage(conversationId: string): Promise<void> {
    await this.messaging.open(conversationId);
    this.router.navigate(['/chat']);
  }

  async openUser(userId: string): Promise<void> {
    await this.messaging.open(this.messaging.directIdWith(userId));
    this.router.navigate(['/chat']);
  }
}
