import { Component, OnInit, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ProfileService } from '../../core/profile.service';
import { Profile } from '../../core/models';
import { errorMessage } from '../../core/http-error';

/**
 * View and edit the signed-in user's own profile — the Phase 2 web deliverable.
 * Loads via GET /profiles/me and saves edited fields via PATCH.
 *
 * A 404 here is expected, not exceptional: profile provisioning runs from
 * Auth's postConfirmation trigger, which isn't attached to the Cognito pool
 * yet (see CLAUDE.md), so a freshly-confirmed account may have no profile.
 * We surface that as a distinct, explained state rather than a raw error.
 */
@Component({
  selector: 'app-profile',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <section class="card">
      <h1>Your profile</h1>

      @if (loading()) {
        <p class="hint">Loading…</p>
      } @else if (missing()) {
        <p class="err">No profile found for your account.</p>
        <p class="hint">
          Profiles are provisioned when Auth's <code>postConfirmation</code> trigger fires.
          That trigger isn't wired to the pool yet, so accounts confirmed manually won't have
          one. Provision it via <code>POST /internal/profiles</code> (see the Profile service
          README), then reload.
        </p>
      } @else {
        <form [formGroup]="form" (ngSubmit)="save()">
          <label>
            Display name
            <input type="text" formControlName="displayName" maxlength="64" />
          </label>
          <label>
            Avatar URL
            <input type="url" formControlName="avatarUrl" maxlength="512" placeholder="(optional)" />
          </label>
          <label>
            Bio
            <textarea formControlName="bio" maxlength="512" rows="3" placeholder="(optional)"></textarea>
          </label>

          @if (error()) {
            <p class="err">{{ error() }}</p>
          }
          @if (saved()) {
            <p class="ok">Saved.</p>
          }

          <button type="submit" [disabled]="form.invalid || form.pristine || saving()">
            {{ saving() ? 'Saving…' : 'Save changes' }}
          </button>
        </form>

        <dl class="meta">
          <dt>User ID</dt><dd>{{ profile()?.userId }}</dd>
          <dt>Joined</dt><dd>{{ profile()?.createdAt }}</dd>
        </dl>
      }

      <p class="alt"><a routerLink="/mfa-enroll">Set up two-factor auth</a></p>
    </section>
  `,
})
export class ProfileComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly profiles = inject(ProfileService);

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly missing = signal(false);
  readonly error = signal<string | null>(null);
  readonly saved = signal(false);
  readonly profile = signal<Profile | null>(null);

  readonly form = this.fb.nonNullable.group({
    displayName: ['', [Validators.required, Validators.maxLength(64)]],
    avatarUrl: ['', [Validators.maxLength(512)]],
    bio: ['', [Validators.maxLength(512)]],
  });

  ngOnInit(): void {
    this.profiles.getMine().subscribe({
      next: (p) => {
        this.loading.set(false);
        this.applyProfile(p);
      },
      error: (err) => {
        this.loading.set(false);
        if (err?.status === 404) {
          this.missing.set(true);
        } else {
          this.error.set(errorMessage(err));
        }
      },
    });
  }

  save(): void {
    const current = this.profile();
    if (this.form.invalid || !current) return;
    this.saving.set(true);
    this.error.set(null);
    this.saved.set(false);

    const { displayName, avatarUrl, bio } = this.form.getRawValue();
    // Empty optional fields are sent as null (clear), not "" — matching the
    // Profile service's "null clears" contract.
    this.profiles
      .update(current.userId, {
        displayName,
        avatarUrl: avatarUrl.trim() === '' ? null : avatarUrl,
        bio: bio.trim() === '' ? null : bio,
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

  private applyProfile(p: Profile): void {
    this.profile.set(p);
    this.form.reset({
      displayName: p.displayName ?? '',
      avatarUrl: p.avatarUrl ?? '',
      bio: p.bio ?? '',
    });
  }
}
