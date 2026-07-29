import { Component, OnInit, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PresencePanelComponent } from '../presence/presence-panel';
import { AuthService } from '../../core/auth.service';
import { ProfileService } from '../../core/profile.service';
import { MediaService } from '../../core/media.service';
import { Profile } from '../../core/models';
import { errorMessage } from '../../core/http-error';

const MAX_TAGS = 10;
const MAX_LINKS = 10;

/**
 * View and edit the signed-in user's own profile.
 *
 * Phase 10 additions: a photo avatar (uploaded through the Media service, so it's
 * shrunk ≤1024 like any other image), phone, up to 10 external links, up to 10
 * tags, and a visibility setting (PUBLIC/CONTACTS/PRIVATE) that governs whether
 * the profile surfaces in people-search.
 */
@Component({
  selector: 'app-profile',
  imports: [ReactiveFormsModule, RouterLink, PresencePanelComponent],
  template: `
    <section class="card">
      <h1>Your profile</h1>

      @if (loading()) {
        <p class="hint">Loading…</p>
      } @else if (missing()) {
        <p class="err">No profile found for your account.</p>
        <p class="hint">
          Profiles are provisioned on first visit (or by Auth's <code>postConfirmation</code>
          trigger). Reload, or provision via <code>POST /internal/profiles</code>.
        </p>
      } @else {
        <form [formGroup]="form" (ngSubmit)="save()">
          <div class="avatar-row">
            <div class="avatar">
              @if (avatarMediaId(); as id) {
                @if (media.ready(id)) {
                  <img [src]="media.displayUrl(id)" alt="avatar" />
                } @else {
                  <div class="ph">⏳</div>
                }
              } @else {
                <div class="ph">no photo</div>
              }
            </div>
            <div class="avatar-actions">
              <label class="upload" [class.busy]="avatarBusy()">
                {{ avatarBusy() ? 'Uploading…' : 'Choose photo' }}
                <input type="file" accept="image/*" hidden [disabled]="avatarBusy()" (change)="onAvatar($event)" />
              </label>
              @if (avatarMediaId()) {
                <button type="button" class="link" (click)="removeAvatar()">Remove</button>
              }
            </div>
          </div>

          <label>
            Display name
            <input type="text" formControlName="displayName" maxlength="64" />
          </label>
          <label>
            Phone
            <input type="tel" formControlName="phone" maxlength="32" placeholder="(optional)" />
          </label>
          <label>
            Bio
            <textarea formControlName="bio" maxlength="512" rows="3" placeholder="(optional)"></textarea>
          </label>
          <label>
            Tags
            <input type="text" formControlName="tagsText" placeholder="comma-separated, up to 10" />
            <small class="hint">e.g. photography, hiking, java</small>
          </label>
          <label>
            Links
            <textarea formControlName="linksText" rows="3" placeholder="one URL per line, up to 10"></textarea>
          </label>
          <label>
            Visibility
            <select formControlName="visibility">
              <option value="PUBLIC">Public — discoverable in search</option>
              <option value="CONTACTS">Contacts only</option>
              <option value="PRIVATE">Private</option>
            </select>
            <small class="hint">Only <b>Public</b> profiles appear in people-search.</small>
          </label>

          @if (error()) { <p class="err">{{ error() }}</p> }
          @if (saved()) { <p class="ok">Saved.</p> }

          <button type="submit" [disabled]="form.invalid || (form.pristine && !changed()) || saving() || avatarBusy()">
            {{ saving() ? 'Saving…' : 'Save changes' }}
          </button>
        </form>

        <dl class="meta">
          <dt>User ID</dt><dd>{{ profile()?.userId }}</dd>
          <dt>Joined</dt><dd>{{ profile()?.createdAt }}</dd>
        </dl>
      }

      <p class="alt"><a routerLink="/mfa-enroll">Set up two-factor auth</a></p>

      <div class="danger">
        <h2>Danger zone</h2>
        <p class="hint">
          Permanently delete your account — your profile, contacts, and sign-in.
          This cannot be undone.
        </p>
        @if (deleteError()) { <p class="err">{{ deleteError() }}</p> }
        <button type="button" class="danger-btn" [disabled]="deleting()" (click)="confirmDelete()">
          {{ deleting() ? 'Deleting…' : 'Delete account' }}
        </button>
      </div>
    </section>

    <app-presence-panel />
  `,
  styles: [
    `
      .avatar-row { display: flex; gap: 1rem; align-items: center; margin-bottom: 0.5rem; }
      .avatar { width: 88px; height: 88px; border-radius: 50%; overflow: hidden; background: var(--bg); border: 1px solid var(--border); display: flex; align-items: center; justify-content: center; flex: none; }
      .avatar img { width: 100%; height: 100%; object-fit: cover; }
      .avatar .ph { color: var(--muted); font-size: 0.75rem; text-align: center; }
      .avatar-actions { display: flex; flex-direction: column; gap: 0.35rem; align-items: flex-start; }
      .upload { cursor: pointer; padding: 0.4rem 0.7rem; border: 1px solid var(--border); border-radius: 8px; font-size: 0.85rem; }
      .upload.busy { opacity: 0.5; cursor: wait; }
      small.hint { display: block; color: var(--muted); font-size: 0.75rem; margin-top: 0.15rem; }
      .danger { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid var(--border); }
      .danger h2 { font-size: 0.95rem; color: var(--err, #c0392b); margin: 0 0 0.25rem; }
      .danger-btn { background: var(--err, #c0392b); color: #fff; border: none; }
      .danger-btn:disabled { opacity: 0.6; cursor: wait; }
    `,
  ],
})
export class ProfileComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly profiles = inject(ProfileService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly media = inject(MediaService);

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly missing = signal(false);
  readonly error = signal<string | null>(null);
  readonly saved = signal(false);
  readonly profile = signal<Profile | null>(null);

  readonly deleting = signal(false);
  readonly deleteError = signal<string | null>(null);

  // Avatar isn't a form control (it's an upload), so track it + a dirty flag
  // separately, and fold that into the Save button's enabled state.
  readonly avatarMediaId = signal<string | null>(null);
  readonly avatarBusy = signal(false);
  readonly changed = signal(false);

  readonly form = this.fb.nonNullable.group({
    displayName: ['', [Validators.required, Validators.maxLength(64)]],
    phone: ['', [Validators.maxLength(32)]],
    bio: ['', [Validators.maxLength(512)]],
    tagsText: [''],
    linksText: [''],
    visibility: ['PUBLIC' as Profile['visibility'], [Validators.required]],
  });

  ngOnInit(): void {
    this.profiles.getMine().subscribe({
      next: (p) => {
        this.loading.set(false);
        this.applyProfile(p);
      },
      error: (err) => {
        this.loading.set(false);
        if (err?.status === 404) this.missing.set(true);
        else this.error.set(errorMessage(err));
      },
    });
  }

  async onAvatar(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = ''; // allow re-picking the same file
    if (!file) return;
    this.avatarBusy.set(true);
    this.error.set(null);
    this.saved.set(false);
    try {
      const mediaId = await this.media.upload(file);
      this.avatarMediaId.set(mediaId);
      this.changed.set(true);
    } catch (err) {
      this.error.set(errorMessage(err));
    } finally {
      this.avatarBusy.set(false);
    }
  }

  removeAvatar(): void {
    this.avatarMediaId.set(null);
    this.changed.set(true);
    this.saved.set(false);
  }

  save(): void {
    const current = this.profile();
    if (this.form.invalid || !current) return;
    this.saving.set(true);
    this.error.set(null);
    this.saved.set(false);

    const v = this.form.getRawValue();
    this.profiles
      .update(current.userId, {
        displayName: v.displayName,
        avatarMediaId: this.avatarMediaId(),
        bio: blankToNull(v.bio),
        phone: blankToNull(v.phone),
        tags: splitList(v.tagsText, /,/, MAX_TAGS),
        links: splitList(v.linksText, /[\n,]/, MAX_LINKS),
        visibility: v.visibility,
      })
      .subscribe({
        next: (p) => {
          this.saving.set(false);
          this.saved.set(true);
          this.applyProfile(p);
        },
        error: (err) => {
          this.saving.set(false);
          this.error.set(errorMessage(err));
        },
      });
  }

  confirmDelete(): void {
    const ok = window.confirm(
      'Permanently delete your account? This removes your profile and contacts, ' +
        'signs you out everywhere, and cannot be undone.',
    );
    if (!ok) return;
    this.deleting.set(true);
    this.deleteError.set(null);
    this.auth.deleteAccount().subscribe({
      next: () => this.router.navigate(['/login']),
      error: (err) => {
        this.deleting.set(false);
        this.deleteError.set(errorMessage(err));
      },
    });
  }

  private applyProfile(p: Profile): void {
    this.profile.set(p);
    this.avatarMediaId.set(p.avatarMediaId ?? null);
    this.changed.set(false);
    this.form.reset({
      displayName: p.displayName ?? '',
      phone: p.phone ?? '',
      bio: p.bio ?? '',
      tagsText: (p.tags ?? []).join(', '),
      linksText: (p.links ?? []).join('\n'),
      visibility: p.visibility ?? 'PUBLIC',
    });
  }
}

/** Empty/whitespace → null (clear), matching the Profile service's "null clears". */
function blankToNull(value: string): string | null {
  return value.trim() === '' ? null : value.trim();
}

/** Split, trim, drop blanks, dedupe, cap — for the tags/links free-text inputs. */
function splitList(text: string, sep: RegExp, max: number): string[] {
  const seen = new Set<string>();
  for (const raw of text.split(sep)) {
    const item = raw.trim();
    if (item && !seen.has(item)) seen.add(item);
    if (seen.size >= max) break;
  }
  return [...seen];
}
