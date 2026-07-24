import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { TokenStore } from './token-store';
import { Contact } from './models';

/**
 * The signed-in user's contacts (Phase 11). Loaded on login and kept in a
 * signal so any component (contacts page, search results, chat) can both render
 * the list and cheaply check `isContact(id)` to toggle add/remove affordances.
 *
 * Mutations are optimistic-ish: we call the API then update the signal from the
 * response, so the UI reflects the server's truth.
 */
@Injectable({ providedIn: 'root' })
export class ContactsService {
  private readonly http = inject(HttpClient);
  private readonly tokenStore = inject(TokenStore);

  readonly contacts = signal<Contact[]>([]);
  /** Fast membership lookup for "am I already following this user?" */
  readonly ids = computed(() => new Set(this.contacts().map((c) => c.userId)));

  constructor() {
    // (Re)load whenever auth state flips to signed-in; clear on sign-out.
    effect(() => {
      if (this.tokenStore.isAuthenticated()) this.load();
      else this.contacts.set([]);
    });
  }

  load(): void {
    this.http.get<{ contacts: Contact[] }>('/contacts').subscribe({
      next: (r) => this.contacts.set(r.contacts ?? []),
      error: () => {
        /* leave whatever we had; the page shows its own error if needed */
      },
    });
  }

  isContact(userId: string): boolean {
    return this.ids().has(userId);
  }

  async add(contactId: string): Promise<void> {
    const contact = await firstValueFrom(this.http.post<Contact>('/contacts', { contactId }));
    this.contacts.update((list) =>
      list.some((c) => c.userId === contact.userId) ? list : [...list, contact],
    );
  }

  async remove(contactId: string): Promise<void> {
    await firstValueFrom(this.http.delete(`/contacts/${encodeURIComponent(contactId)}`));
    this.contacts.update((list) => list.filter((c) => c.userId !== contactId));
  }
}
