package dev.rstrickland.chat.ui.chat;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.rstrickland.chat.R;
import dev.rstrickland.chat.model.MediaModels;
import dev.rstrickland.chat.net.MediaBlobClient;
import dev.rstrickland.chat.net.api.MediaApi;

/**
 * Renders a message's media attachment inline, mirroring the web client:
 *   - image  → inline thumbnail; tap opens a full-screen viewer
 *   - video  → inline poster + ▶ overlay; tap plays full-screen (VideoView)
 *   - audio  → a tap-to-play chip (▶/⏸), one at a time
 *
 * Media processing is async, so it polls {@code GET /media/{id}} until ready
 * (like {@link dev.rstrickland.chat.net.AvatarLoader}), then fetches bytes via
 * {@link MediaBlobClient} — which handles the emulator/MinIO presign host quirk,
 * so the platform players (VideoView/MediaPlayer) get a reachable LOCAL file.
 *
 * One instance per {@link MessageAdapter}; owns the single audio player and is
 * {@link #release() released} with the adapter.
 */
final class MessageMediaBinder {

    private static final int POLL_ATTEMPTS = 60;      // ~90s while a just-sent file is processed
    private static final long POLL_INTERVAL_MS = 1500L;

    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final MediaApi mediaApi;
    /** Local cache of downloaded bytes, keyed by mediaId, for playback. */
    private final Map<String, File> fileCache = new HashMap<>();

    // Single audio player — only one clip plays at a time.
    private MediaPlayer audioPlayer;
    private String playingAudioId;
    private TextView playingChip;
    private boolean playingChipMine;

    MessageMediaBinder(MediaApi mediaApi) {
        this.mediaApi = mediaApi;
    }

    /**
     * Bind {@code mediaId} into a message row's media views. Resets them first so a
     * recycled row never shows a stale attachment, and tag-guards async results.
     */
    void bind(String mediaId, boolean mine, FrameLayout container,
              ImageView image, ImageView play, TextView chip) {
        // Reset.
        container.setTag(mediaId);
        container.setVisibility(View.GONE);
        container.setOnClickListener(null);
        image.setImageDrawable(null);
        image.setOnClickListener(null);
        play.setVisibility(View.GONE);
        chip.setVisibility(View.GONE);
        chip.setOnClickListener(null);

        if (mediaId == null || mediaId.isEmpty()) return;

        // Placeholder while we resolve the (async) processing status.
        styleChip(chip, mine);
        chip.setVisibility(View.VISIBLE);
        chip.setText("⏳ loading…");

        IO.execute(() -> {
            MediaModels.MediaView v = pollReady(mediaId);
            main.post(() -> {
                if (!mediaId.equals(container.getTag())) return; // recycled/superseded
                if (v == null || v.isFailed() || !v.isReady()) {
                    chip.setText("⚠️ media unavailable");
                    return;
                }
                render(mediaId, v, mine, container, image, play, chip);
            });
        });
    }

    /** Poll GET /media/{id} until ready/failed (or the cap). Runs on a background thread. */
    private MediaModels.MediaView pollReady(String mediaId) {
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

    private void render(String mediaId, MediaModels.MediaView v, boolean mine,
                        FrameLayout container, ImageView image, ImageView play, TextView chip) {
        String ct = v.contentType != null ? v.contentType : "";
        if (ct.startsWith("image/")) {
            chip.setVisibility(View.GONE);
            loadBitmap(mediaId, v.displayUrl(), container, image);
            container.setVisibility(View.VISIBLE);
            View.OnClickListener open = view -> showImageDialog(view.getContext(), mediaId, v.url);
            image.setOnClickListener(open);
            container.setOnClickListener(open);
        } else if (ct.startsWith("video/")) {
            chip.setVisibility(View.GONE);
            // Poster: the extracted-frame thumbnail if present, else nothing (blank bubble).
            if (v.thumbnailUrl != null) loadBitmap(mediaId, v.thumbnailUrl, container, image);
            play.setVisibility(View.VISIBLE);
            container.setVisibility(View.VISIBLE);
            container.setOnClickListener(view -> playVideo(view.getContext(), mediaId, v.url));
        } else if (ct.startsWith("audio/")) {
            container.setVisibility(View.GONE);
            chip.setVisibility(View.VISIBLE);
            styleChip(chip, mine);
            setAudioChipLabel(chip, false);
            chip.setOnClickListener(view -> toggleAudio(view.getContext(), mediaId, v.url, chip, mine));
        } else {
            // Non-media file (the picker restricts to image/video/audio, so this is a fallback).
            container.setVisibility(View.GONE);
            chip.setVisibility(View.VISIBLE);
            styleChip(chip, mine);
            chip.setText("📎 Attachment");
        }
    }

    // ---- images ----

    /** Fetch a bitmap off-thread and apply it, guarded by the container's mediaId tag. */
    private void loadBitmap(String mediaId, String url, FrameLayout container, ImageView image) {
        IO.execute(() -> {
            try {
                Bitmap bmp = MediaBlobClient.get().getBitmap(url);
                main.post(() -> {
                    if (mediaId.equals(container.getTag())) image.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
                // Leave the placeholder background; non-fatal.
            }
        });
    }

    private void showImageDialog(Context ctx, String mediaId, String fullUrl) {
        ImageView full = new ImageView(ctx);
        full.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);

        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        dialog.setContentView(full);
        full.setOnClickListener(v -> dialog.dismiss());
        dialog.show();

        IO.execute(() -> {
            try {
                Bitmap bmp = MediaBlobClient.get().getBitmap(fullUrl);
                main.post(() -> {
                    if (dialog.isShowing()) full.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
                // Nothing to show; the black dialog dismisses on tap.
            }
        });
    }

    // ---- video ----

    private void playVideo(Context ctx, String mediaId, String url) {
        // Fetch bytes to a local file first (the platform VideoView can't reach the
        // presign host on the emulator), then play from the file.
        IO.execute(() -> {
            File file = ensureLocal(ctx, mediaId, url, ".mp4");
            if (file == null) return;
            main.post(() -> showVideoDialog(ctx, file));
        });
    }

    private void showVideoDialog(Context ctx, File file) {
        FrameLayout frame = new FrameLayout(ctx);
        frame.setBackgroundColor(Color.BLACK);
        VideoView video = new VideoView(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        video.setLayoutParams(lp);
        frame.addView(video);

        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(frame);

        MediaController controller = new MediaController(ctx);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setVideoPath(file.getAbsolutePath());
        video.setOnPreparedListener(mp -> video.start());
        video.setOnCompletionListener(mp -> dialog.dismiss());
        frame.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> video.stopPlayback());
        dialog.show();
    }

    // ---- audio ----

    private void toggleAudio(Context ctx, String mediaId, String url, TextView chip, boolean mine) {
        if (mediaId.equals(playingAudioId) && audioPlayer != null) {
            if (audioPlayer.isPlaying()) {
                audioPlayer.pause();
                setAudioChipLabel(chip, false);
            } else {
                audioPlayer.start();
                setAudioChipLabel(chip, true);
            }
            return;
        }
        stopAudio(); // switch clips: stop whatever's playing and reset its chip

        chip.setText("⏳ loading…");
        IO.execute(() -> {
            File file = ensureLocal(ctx, mediaId, url, ".mp3");
            main.post(() -> {
                if (file == null) {
                    setAudioChipLabel(chip, false);
                    return;
                }
                try {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setDataSource(file.getAbsolutePath());
                    mp.setOnCompletionListener(p -> {
                        setAudioChipLabel(chip, false);
                        stopAudio();
                    });
                    mp.prepare();
                    mp.start();
                    audioPlayer = mp;
                    playingAudioId = mediaId;
                    playingChip = chip;
                    playingChipMine = mine;
                    setAudioChipLabel(chip, true);
                } catch (Exception e) {
                    setAudioChipLabel(chip, false);
                }
            });
        });
    }

    private void stopAudio() {
        if (playingChip != null) setAudioChipLabel(playingChip, false);
        if (audioPlayer != null) {
            try {
                audioPlayer.release();
            } catch (Exception ignored) {
            }
            audioPlayer = null;
        }
        playingAudioId = null;
        playingChip = null;
    }

    private void setAudioChipLabel(TextView chip, boolean playing) {
        chip.setText(playing ? "⏸  Audio" : "▶  Audio");
    }

    // ---- shared ----

    /** Download {@code url} to a cached local file (once), returning it, or null on failure. */
    private File ensureLocal(Context ctx, String mediaId, String url, String suffix) {
        File cached = fileCache.get(mediaId);
        if (cached != null && cached.exists()) return cached;
        try {
            File dest = new File(ctx.getCacheDir(), "media_" + safe(mediaId) + suffix);
            MediaBlobClient.get().downloadToFile(url, dest);
            fileCache.put(mediaId, dest);
            return dest;
        } catch (Exception e) {
            return null;
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void styleChip(TextView chip, boolean mine) {
        chip.setBackgroundResource(mine ? R.drawable.bg_bubble_mine : R.drawable.bg_bubble_theirs);
        chip.setTextColor(ContextCompat.getColor(
                chip.getContext(), mine ? R.color.bubble_mine_text : R.color.bubble_theirs_text));
    }

    /** Release the audio player — call when the owning adapter/fragment goes away. */
    void release() {
        stopAudio();
    }
}
