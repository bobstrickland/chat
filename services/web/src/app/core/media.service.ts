import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, firstValueFrom, of } from 'rxjs';

interface MediaView {
  mediaId: string;
  contentType: string;
  status: 'pending' | 'processing' | 'ready' | 'failed';
  url: string;
  thumbnailUrl: string | null;
}

/**
 * Client for the Media service. Processing is ASYNC now: /complete just enqueues
 * (returns "processing"), and a worker shrinks/thumbnails in the background. So
 * the render helpers POLL `GET /media/{id}` until status is "ready" (or "failed")
 * — everything reads a signal, so the view updates itself when the poll lands.
 */
@Injectable({ providedIn: 'root' })
export class MediaService {
  private readonly http = inject(HttpClient);
  private readonly views = signal<Record<string, MediaView>>({});
  private readonly polling = new Set<string>();

  async upload(file: File): Promise<string> {
    const { mediaId, uploadUrl } = await firstValueFrom(
      this.http.post<{ mediaId: string; uploadUrl: string }>('/media/uploads', {
        contentType: file.type || 'application/octet-stream',
      }),
    );
    const res = await fetch(uploadUrl, {
      method: 'PUT',
      body: file,
      headers: { 'content-type': file.type || 'application/octet-stream' },
    });
    if (!res.ok) throw new Error(`upload failed (${res.status})`);
    // Enqueue processing — returns fast ("processing"); we don't wait for the transcode.
    await firstValueFrom(this.http.post(`/media/${encodeURIComponent(mediaId)}/complete`, {}));
    return mediaId;
  }

  ready(mediaId: string): boolean {
    return this.ensure(mediaId)?.status === 'ready';
  }

  failed(mediaId: string): boolean {
    return this.ensure(mediaId)?.status === 'failed';
  }

  /** How to render this media, once ready. null while processing/loading. */
  kind(mediaId: string): 'image' | 'video' | 'audio' | 'file' | null {
    const ct = this.readyView(mediaId)?.contentType;
    if (!ct) return null;
    if (ct.startsWith('image/')) return 'image';
    if (ct.startsWith('video/')) return 'video';
    if (ct.startsWith('audio/')) return 'audio';
    return 'file';
  }

  /** Inline URL — thumbnail (images) else the original. null until ready. */
  displayUrl(mediaId: string): string | null {
    const v = this.readyView(mediaId);
    return v ? v.thumbnailUrl ?? v.url : null;
  }

  fullUrl(mediaId: string): string | null {
    return this.readyView(mediaId)?.url ?? null;
  }

  thumbUrl(mediaId: string): string | null {
    return this.readyView(mediaId)?.thumbnailUrl ?? null;
  }

  private readyView(mediaId: string): MediaView | null {
    const v = this.ensure(mediaId);
    return v && v.status === 'ready' ? v : null;
  }

  /** Return the cached view (reactive) and kick off polling if not yet terminal. */
  private ensure(mediaId: string): MediaView | null {
    const v = this.views()[mediaId];
    if (v && (v.status === 'ready' || v.status === 'failed')) return v;
    if (!this.polling.has(mediaId)) {
      this.polling.add(mediaId);
      this.poll(mediaId, 0);
    }
    return v ?? null;
  }

  private poll(mediaId: string, attempt: number): void {
    this.http
      .get<MediaView>(`/media/${encodeURIComponent(mediaId)}`)
      .pipe(catchError(() => of(null)))
      .subscribe((v) => {
        if (v) this.views.update((m) => ({ ...m, [mediaId]: v }));
        const terminal = v && (v.status === 'ready' || v.status === 'failed');
        if (terminal || attempt >= 60) {
          this.polling.delete(mediaId); // done, or gave up after ~90s
          return;
        }
        setTimeout(() => this.poll(mediaId, attempt + 1), 1500);
      });
  }
}
