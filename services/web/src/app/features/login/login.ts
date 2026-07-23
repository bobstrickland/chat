import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { errorMessage } from '../../core/http-error';

/**
 * Two-step login. Step 1 posts credentials; if the account has TOTP MFA the
 * response carries `mfaRequired` + a short-lived `mfaSession` instead of
 * tokens, and we switch to step 2 to collect the 6-digit code.
 */
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <section class="card">
      <h1>Sign in</h1>

      @if (stage() === 'credentials') {
        <form [formGroup]="credentials" (ngSubmit)="submitCredentials()">
          <label>
            Email
            <input type="email" formControlName="email" autocomplete="email" />
          </label>
          <label>
            Password
            <input type="password" formControlName="password" autocomplete="current-password" />
          </label>

          @if (error()) {
            <p class="err">{{ error() }}</p>
          }

          <button type="submit" [disabled]="credentials.invalid || loading()">
            {{ loading() ? 'Signing in…' : 'Sign in' }}
          </button>
        </form>
        <p class="alt">No account? <a routerLink="/register">Create one</a></p>
      } @else {
        <p class="hint">Enter the 6-digit code from your authenticator app.</p>
        <form [formGroup]="mfa" (ngSubmit)="submitMfa()">
          <label>
            Authentication code
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

          <button type="submit" [disabled]="mfa.invalid || loading()">
            {{ loading() ? 'Verifying…' : 'Verify' }}
          </button>
        </form>
        <p class="alt"><a href="#" (click)="restart($event)">Start over</a></p>
      }
    </section>
  `,
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly stage = signal<'credentials' | 'mfa'>('credentials');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  private pendingEmail = '';
  private mfaSession = '';

  readonly credentials = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]],
  });

  readonly mfa = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
  });

  submitCredentials(): void {
    if (this.credentials.invalid) return;
    this.loading.set(true);
    this.error.set(null);
    const { email, password } = this.credentials.getRawValue();

    this.auth.login(email, password).subscribe({
      next: (res) => {
        this.loading.set(false);
        if (res.mfaRequired && res.mfaSession) {
          this.pendingEmail = email;
          this.mfaSession = res.mfaSession;
          this.stage.set('mfa');
        } else {
          // Tokens were persisted by AuthService.login.
          this.router.navigate(['/profile']);
        }
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }

  submitMfa(): void {
    if (this.mfa.invalid) return;
    this.loading.set(true);
    this.error.set(null);
    const { code } = this.mfa.getRawValue();

    this.auth.verifyMfaChallenge(this.pendingEmail, this.mfaSession, code).subscribe({
      next: () => {
        this.loading.set(false);
        this.router.navigate(['/profile']);
      },
      error: (err) => {
        this.loading.set(false);
        // A stale/used session can't be recovered — send them back to step 1.
        this.error.set(errorMessage(err));
      },
    });
  }

  restart(event: Event): void {
    event.preventDefault();
    this.mfa.reset();
    this.error.set(null);
    this.stage.set('credentials');
  }
}
