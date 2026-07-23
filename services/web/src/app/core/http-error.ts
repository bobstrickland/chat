import { HttpErrorResponse } from '@angular/common/http';

/**
 * Both backends return errors as `{ "error": "message" }` with a 4xx status.
 * Pull that message out, with sensible fallbacks for network/opaque failures.
 */
export function errorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.error && typeof err.error.error === 'string') return err.error.error;
    if (err.status === 0) return 'Cannot reach the server. Is the stack running?';
    return `Request failed (${err.status})`;
  }
  return 'Unexpected error';
}
