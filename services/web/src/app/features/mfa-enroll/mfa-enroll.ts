import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { errorMessage } from '../../core/http-error';

/**
 * TOTP enrollment for an already-signed-in user. Calls enrollMfa to get a
 * shared secret, the user adds it to an authenticator app, then confirms with
 * a generated code. Once confirmed, MFA is required on subsequent logins.
 */
@Component({
  selector: 'app-mfa-enroll',
  imports: [ReactiveFormsModule],
  template: `
    <section class="card">
      <h1>Enable two-factor auth</h1>

      @if (done()) {
        <p class="ok">Two-factor authentication is now enabled for your account.</p>
        <p class="hint">You'll be asked for a code from your authenticator next time you sign in.</p>
      } @else if (secret()) {
        <p class="hint">Add this secret to your authenticator app (or scan the otpauth URL):</p>
        <p class="secret">{{ secret() }}</p>
        <details>
          <summary>otpauth URL</summary>
          <p class="secret small">{{ otpauth() }}</p>
        </details>

        <form [formGroup]="form" (ngSubmit)="confirm()">
          <label>
            Enter a code to confirm
            <input
              type="text"
              formControlName="code"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
            />
          </label>
          @if (error()) {
            <p class="err">{{ error() }}</p>
          }
          <button type="submit" [disabled]="form.invalid || loading()">
            {{ loading() ? 'Confirming…' : 'Confirm' }}
          </button>
        </form>
      } @else {
        <p class="hint">Generate a secret to set up two-factor authentication.</p>
        @if (error()) {
          <p class="err">{{ error() }}</p>
        }
        <button type="button" (click)="begin()" [disabled]="loading()">
          {{ loading() ? 'Working…' : 'Begin setup' }}
        </button>
      }
    </section>
  `,
})
export class MfaEnrollComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly secret = signal<string | null>(null);
  readonly otpauth = signal<string | null>(null);
  readonly done = signal(false);

  readonly form = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
  });

  begin(): void {
    this.loading.set(true);
    this.error.set(null);
    this.auth.enrollMfa().subscribe({
      next: (res) => {
        this.loading.set(false);
        this.secret.set(res.secretCode);
        this.otpauth.set(res.otpauthUrl);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }

  confirm(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);
    this.auth.confirmMfaEnrollment(this.form.getRawValue().code).subscribe({
      next: () => {
        this.loading.set(false);
        this.done.set(true);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }
}
