import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { TokenStore } from './token-store';

/**
 * Gate for protected routes. Returns a UrlTree (redirect to /login) rather
 * than a boolean when unauthenticated — the idiomatic Angular way to send an
 * unauthorized visitor somewhere instead of just blocking.
 */
export const authGuard: CanActivateFn = () => {
  const tokenStore = inject(TokenStore);
  const router = inject(Router);
  return tokenStore.isAuthenticated() ? true : router.parseUrl('/login');
};
