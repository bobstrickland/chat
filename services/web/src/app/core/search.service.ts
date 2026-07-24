import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface MessageHit {
  messageId: string;
  conversationId: string;
  senderId: string;
  body: string;
  sentAt: string;
}

export interface UserHit {
  userId: string;
  displayName: string;
  bio?: string;
}

export interface SearchResults {
  messages: MessageHit[];
  users: UserHit[];
}

/** Thin client for the Search service. Auth header is added by the interceptor. */
@Injectable({ providedIn: 'root' })
export class SearchService {
  private readonly http = inject(HttpClient);

  search(q: string): Observable<SearchResults> {
    return this.http.get<SearchResults>('/search', { params: { q } });
  }
}
