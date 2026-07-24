import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { TokenStore } from './core/token-store';
import { AuthService } from './core/auth.service';
import { PresenceService } from './core/presence.service';
import { ConversationsService } from './core/conversations.service';
import { ContactsService } from './core/contacts.service';
import { PushService } from './core/push.service';

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
  // Keeps the contact list warm session-wide, so isContact() checks are ready
  // on the search/chat pages without a first visit to the contacts page.
  private readonly contacts = inject(ContactsService);
  // Registers web-push on login (best-effort — see PushService).
  private readonly push = inject(PushService);

  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
