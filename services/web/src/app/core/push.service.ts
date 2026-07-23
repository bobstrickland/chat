import { Injectable, effect, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { TokenStore } from './token-store';

/**
 * Web Push registration (Phase 5). On login, if the browser supports push and
 * the user grants permission, this registers a service worker, subscribes to
 * push with the server's VAPID public key, and registers the subscription as a
 * device token — so the Notification service can reach this browser when the
 * user is offline (tab closed / no live socket).
 *
 * Best-effort and non-blocking: no push support, denied permission, or a
 * registration error just means no offline push — the app still works.
 */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly http = inject(HttpClient);
  private readonly tokenStore = inject(TokenStore);

  private registered = false;

  constructor() {
    effect(() => {
      if (this.tokenStore.isAuthenticated() && !this.registered) {
        this.registered = true;
        this.register().catch((err) => console.warn('[push] registration skipped:', err?.message));
      }
      if (!this.tokenStore.isAuthenticated()) {
        this.registered = false;
      }
    });
  }

  private async register(): Promise<void> {
    if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
      return; // browser can't do web push
    }
    const permission = await Notification.requestPermission();
    if (permission !== 'granted') {
      return;
    }

    const { publicKey } = await firstValueFrom(
      this.http.get<{ publicKey: string }>('/push/config'),
    );

    const registration = await navigator.serviceWorker.register('/sw.js');
    // Reuse an existing subscription if present, else create one.
    const subscription =
      (await registration.pushManager.getSubscription()) ??
      (await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(publicKey) as BufferSource,
      }));

    await firstValueFrom(
      this.http.post('/device-tokens', {
        deviceId: this.deviceId(),
        platform: 'web',
        subscription: subscription.toJSON(),
      }),
    );
  }

  /** Stable per-browser id so re-login updates the same token row, not a new one. */
  private deviceId(): string {
    let id = localStorage.getItem('chat.deviceId');
    if (!id) {
      id = crypto.randomUUID();
      localStorage.setItem('chat.deviceId', id);
    }
    return id;
  }
}

/** VAPID public key (base64url string) → Uint8Array for applicationServerKey. */
function urlBase64ToUint8Array(base64: string): Uint8Array {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4);
  const b64 = (base64 + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(b64);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}
