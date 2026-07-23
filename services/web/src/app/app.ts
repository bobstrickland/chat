import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { TokenStore } from './core/token-store';
import { AuthService } from './core/auth.service';
import { PresenceService } from './core/presence.service';
import { ConversationsService } from './core/conversations.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  // `isAuthenticated` is a signal, read directly in the template — it re-renders
  // the nav the moment tokens are set or cleared.
  protected readonly tokenStore = inject(TokenStore);

  // Injected purely to instantiate them at app start: their constructors set up
  // effects/subscriptions that must run session-wide (presence socket, and the
  // inbox listener that keeps the unread badge live from any page).
  private readonly presence = inject(PresenceService);
  protected readonly conversations = inject(ConversationsService);

  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
