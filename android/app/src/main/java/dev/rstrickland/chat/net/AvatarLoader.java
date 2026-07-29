package dev.rstrickland.chat.net;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.rstrickland.chat.model.MediaModels;
import dev.rstrickland.chat.net.api.MediaApi;

/**
 * Resolves a profile's {@code avatarMediaId} to a photo and shows it in an
 * ImageView — the shared "display an avatar" helper (nav header, profile screen,
 * and later list rows). Never touches the fallback (initials) view; the caller
 * layers this image over it, so a missing/failed avatar just leaves the initials.
 *
 * Media processing is async, so it polls {@code GET /media/{id}} until ready,
 * then loads the bytes via {@link MediaBlobClient} (which handles the
 * emulator/MinIO presign host quirk). All network work is off the main thread.
 */
public final class AvatarLoader {
    private AvatarLoader() {}

    private static final int POLL_ATTEMPTS = 60;      // ~90s cap for a just-uploaded image
    private static final long POLL_INTERVAL_MS = 1500L;

    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Load {@code avatarMediaId} into {@code target}, making it visible on success.
     * A null/blank id hides the target (so the initials fallback shows). Tags the
     * target with the id so a stale async result for a recycled/reused view is
     * dropped.
     */
    public static void load(MediaApi mediaApi, String avatarMediaId, ImageView target) {
        if (target == null) return;
        // Clear first so a recycled/reused view never shows the previous avatar while
        // the new one loads (the initials underneath show through until it lands).
        target.setImageDrawable(null);
        target.setVisibility(View.GONE);
        target.setTag(avatarMediaId);
        if (avatarMediaId == null || avatarMediaId.isEmpty()) return;
        IO.execute(() -> {
            try {
                MediaModels.MediaView view = pollReady(mediaApi, avatarMediaId);
                if (view == null || !view.isReady()) return; // keep the fallback
                Bitmap bmp = MediaBlobClient.get().getBitmap(view.displayUrl());
                MAIN.post(() -> {
                    if (!avatarMediaId.equals(target.getTag())) return; // superseded
                    target.setImageBitmap(bmp);
                    target.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                // Non-fatal: the caller's initials remain as the fallback.
            }
        });
    }

    private static MediaModels.MediaView pollReady(MediaApi mediaApi, String mediaId) {
        MediaModels.MediaView last = null;
        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            try {
                retrofit2.Response<MediaModels.MediaView> res = mediaApi.get(mediaId).execute();
                last = res.isSuccessful() ? res.body() : null;
                if (last != null && (last.isReady() || last.isFailed())) return last;
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return last;
            } catch (Exception e) {
                return last;
            }
        }
        return last;
    }
}
