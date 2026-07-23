import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, of } from 'rxjs';

/**
 * Resolves userIds to display names (from Profile), cached. Used wherever an
 * arbitrary userId needs a human label — notably group message senders, where
 * you can't tell who said what without a name.
 *
 * `displayName()` is safe to call from a template: it reads a signal (so the
 * view re-renders when a name resolves) and fires at most one fetch per id.
 */
@Injectable({ providedIn: 'root' })
export class NamesService {
  private readonly http = inject(HttpClient);
  private readonly cache = signal<Record<string, string>>({});
  private readonly requested = new Set<string>();

  displayName(userId: string): string {
    const known = this.cache()[userId];
    if (known) return known;

    if (!this.requested.has(userId)) {
      this.requested.add(userId);
      this.http
        .get<{ displayName: string }>(`/profiles/${encodeURIComponent(userId)}`)
        .pipe(catchError(() => of(null)))
        .subscribe((profile) => {
          if (profile?.displayName) {
            this.cache.update((m) => ({ ...m, [userId]: profile.displayName }));
          }
        });
    }
    return shortId(userId);
  }
}

function shortId(id: string): string {
  return id.length > 10 ? id.slice(0, 8) + '…' : id;
}
