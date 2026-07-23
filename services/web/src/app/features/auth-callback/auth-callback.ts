import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { errorMessage } from '../../core/http-error';

/**
 * Landing point for the Cognito Hosted UI redirect after Google sign-in. Reads
 * the `?code` (or `?error`), exchanges the code for tokens via the Auth
 * service, then routes into the app. Public route — the user isn't
 * authenticated yet when they arrive here.
 */
@Component({
  selector: 'app-auth-callback',
  imports: [RouterLink],
  template: `
    <section class="card">
      @if (error()) {
        <h1>Sign-in failed</h1>
        <p class="err">{{ error() }}</p>
        <p class="alt"><a routerLink="/login">Back to sign in</a></p>
      } @else {
        <h1>Signing you in…</h1>
        <p class="hint">Completing Google sign-in.</p>
      }
    </section>
  `,
})
export class AuthCallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;

    // Cognito reports an aborted/failed federation via ?error / ?error_description.
    const oauthError = params.get('error_description') ?? params.get('error');
    if (oauthError) {
      this.error.set(oauthError);
      return;
    }

    const code = params.get('code');
    if (!code) {
      this.error.set('No authorization code returned.');
      return;
    }

    this.auth.completeFederatedLogin('google', code).subscribe({
      next: () => this.router.navigate(['/chat']),
      error: (err) => this.error.set(errorMessage(err)),
    });
  }
}
