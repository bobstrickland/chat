import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Profile, ProfileUpdate } from './models';

/**
 * Talks to the Profile service (proxied at /profiles). The bearer token is
 * attached by authInterceptor, so nothing here deals with auth headers.
 */
@Injectable({ providedIn: 'root' })
export class ProfileService {
  private readonly http = inject(HttpClient);

  /** The signed-in user's own profile (server resolves it from the token). */
  getMine(): Observable<Profile> {
    return this.http.get<Profile>('/profiles/me');
  }

  get(userId: string): Observable<Profile> {
    return this.http.get<Profile>(`/profiles/${encodeURIComponent(userId)}`);
  }

  update(userId: string, patch: ProfileUpdate): Observable<Profile> {
    return this.http.patch<Profile>(`/profiles/${encodeURIComponent(userId)}`, patch);
  }
}
