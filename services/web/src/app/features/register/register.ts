import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { errorMessage } from '../../core/http-error';

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <section class="card">
      <h1>Create account</h1>

      @if (registeredEmail()) {
        <p class="ok">
          Account created for <strong>{{ registeredEmail() }}</strong>.
        </p>
        <p class="hint">
          Cognito requires email confirmation before you can sign in. In this dev
          setup there's no inbox wired up — confirm the user with
          <code>admin-confirm-sign-up</code> (see CLAUDE.md), then
          <a routerLink="/login">sign in</a>.
        </p>
      } @else {
        <form [formGroup]="form" (ngSubmit)="submit()">
          <label>
            Email
            <input type="email" formControlName="email" autocomplete="email" />
          </label>
          <label>
            Password
            <input type="password" formControlName="password" autocomplete="new-password" />
          </label>
          <p class="hint">At least 12 characters, with upper, lower, number, and symbol.</p>

          @if (error()) {
            <p class="err">{{ error() }}</p>
          }

          <button type="submit" [disabled]="form.invalid || loading()">
            {{ loading() ? 'Creating…' : 'Create account' }}
          </button>
        </form>
        <p class="alt">Already have an account? <a routerLink="/login">Sign in</a></p>
      }
    </section>
  `,
})
export class RegisterComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly registeredEmail = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(12)]],
  });

  submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);
    const { email, password } = this.form.getRawValue();

    this.auth.register(email, password).subscribe({
      next: (res) => {
        this.loading.set(false);
        this.registeredEmail.set(res.email);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }
}
