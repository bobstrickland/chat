import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { TokenStore } from './token-store';
import { AuthService } from './auth.service';

/**
 * Functional HTTP interceptor. Two jobs:
 *   1. Attach the bearer token to protected (non-/auth) requests.
 *   2. On a 401, refresh once and retry the original request; if that also
 *      fails, drop the session and bounce to /login.
 *
 * RxJava mental model: `catchError` is `onErrorResumeNext`, and `switchMap`
 * is `flatMap` — here it swaps the failed request for "refresh, then reissue".
 *
 * `/auth/*` requests are skipped entirely: they carry no bearer, and letting
 * a 401 from /auth/refresh trigger another refresh would be an infinite loop.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const tokenStore = inject(TokenStore);
  const auth = inject(AuthService);
  const router = inject(Router);

  if (req.url.startsWith('/auth/')) {
    return next(req);
  }

  const token = tokenStore.accessToken;
  const authed = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authed).pipe(
    catchError((err: HttpErrorResponse) => {
      // Only a 401 with a refresh token on hand is recoverable.
      if (err.status !== 401 || !tokenStore.refreshToken) {
        return throwError(() => err);
      }

      return auth.refreshShared().pipe(
        switchMap((fresh) =>
          next(req.clone({ setHeaders: { Authorization: `Bearer ${fresh}` } })),
        ),
        catchError((refreshErr) => {
          // Refresh failed, or the retry 401'd again — the session is dead.
          auth.logout();
          router.navigate(['/login']);
          return throwError(() => refreshErr);
        }),
      );
    }),
  );
};
