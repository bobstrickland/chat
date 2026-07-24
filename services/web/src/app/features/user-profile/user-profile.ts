import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ProfileService } from '../../core/profile.service';
import { ContactsService } from '../../core/contacts.service';
import { MediaService } from '../../core/media.service';
import { MessagingService } from '../../core/messaging.service';
import { TokenStore } from '../../core/token-store';
import { Profile } from '../../core/models';
import { errorMessage } from '../../core/http-error';

/**
 * View ANOTHER user's profile (Phase 11), at /u/:userId. The server enforces
 * visibility: a restricted profile comes back as basic identity only
 * (`restricted: true`), which we render as a "private / contacts-only" state.
 * Includes add/remove-contact and a shortcut to message them.
 */
@Component({
  selector: 'app-user-profile',
  imports: [RouterLink],
  template: `
    <section class="card">
      @if (loading()) {
        <p class="hint">Loading…</p>
      } @else if (error()) {
        <p class="err">{{ error() }}</p>
      } @else if (profile(); as p) {
        <div class="head">
          <div class="avatar">
            @if (p.avatarMediaId && media.ready(p.avatarMediaId)) {
              <img [src]="media.displayUrl(p.avatarMediaId)" alt="avatar" />
            } @else {
              <div class="ph">{{ initials(p.displayName) }}</div>
            }
          </div>
          <div class="who">
            <h1>{{ p.displayName || 'Unknown user' }}</h1>
            @if (!isMe()) {
              <div class="actions">
                @if (contacts.isContact(p.userId)) {
                  <button type="button" class="link" (click)="toggleContact(p.userId)">Remove contact</button>
                } @else {
                  <button type="button" (click)="toggleContact(p.userId)">+ Add contact</button>
                }
                <button type="button" class="link" (click)="message(p.userId)">Message</button>
              </div>
            }
          </div>
        </div>

        @if (p.restricted) {
          <p class="muted restricted">
            This profile is {{ p.visibility === 'PRIVATE' ? 'private' : 'contacts-only' }}.
          </p>
        } @else {
          @if (p.bio) { <p class="bio">{{ p.bio }}</p> }
          <dl class="meta">
            @if (p.phone) { <dt>Phone</dt><dd>{{ p.phone }}</dd> }
            @if (p.tags?.length) {
              <dt>Tags</dt>
              <dd class="tags">@for (t of p.tags; track t) { <span class="tag">{{ t }}</span> }</dd>
            }
            @if (p.links?.length) {
              <dt>Links</dt>
              <dd>@for (l of p.links; track l) { <a [href]="l" target="_blank" rel="noopener">{{ l }}</a><br /> }</dd>
            }
          </dl>
        }
      }
      <p class="alt"><a routerLink="/contacts">← Contacts</a></p>
    </section>
  `,
  styles: [
    `
      .head { display: flex; gap: 1rem; align-items: center; }
      .avatar { width: 72px; height: 72px; border-radius: 50%; overflow: hidden; background: var(--bg); border: 1px solid var(--border); display: flex; align-items: center; justify-content: center; flex: none; }
      .avatar img { width: 100%; height: 100%; object-fit: cover; }
      .avatar .ph { color: var(--muted); font-weight: 600; }
      h1 { margin: 0 0 0.3rem; }
      .actions { display: flex; gap: 0.75rem; align-items: center; }
      .restricted { font-style: italic; }
      .bio { margin: 0.75rem 0; }
      .tags { display: flex; flex-wrap: wrap; gap: 0.3rem; }
      .tag { background: var(--bg); border: 1px solid var(--border); border-radius: 10px; padding: 0.1rem 0.5rem; font-size: 0.8rem; }
      .muted { color: var(--muted); }
    `,
  ],
})
export class UserProfileComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly profiles = inject(ProfileService);
  private readonly messaging = inject(MessagingService);
  private readonly tokenStore = inject(TokenStore);
  protected readonly contacts = inject(ContactsService);
  protected readonly media = inject(MediaService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly profile = signal<Profile | null>(null);
  readonly isMe = computed(() => this.profile()?.userId === this.tokenStore.userId);

  ngOnInit(): void {
    const userId = this.route.snapshot.paramMap.get('userId');
    if (!userId) {
      this.error.set('No user specified.');
      this.loading.set(false);
      return;
    }
    this.profiles.get(userId).subscribe({
      next: (p) => {
        this.profile.set(p);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.status === 404 ? 'No such user.' : errorMessage(err));
      },
    });
  }

  async toggleContact(userId: string): Promise<void> {
    try {
      if (this.contacts.isContact(userId)) await this.contacts.remove(userId);
      else await this.contacts.add(userId);
    } catch (err) {
      this.error.set(errorMessage(err));
    }
  }

  async message(userId: string): Promise<void> {
    await this.messaging.open(this.messaging.directIdWith(userId));
    this.router.navigate(['/chat']);
  }

  initials(name: string | null): string {
    return (name ?? '?').trim().slice(0, 2).toUpperCase() || '?';
  }
}
