package dev.rstrickland.chat.ui.profile;

import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.rstrickland.chat.LoginActivity;
import dev.rstrickland.chat.R;
import dev.rstrickland.chat.databinding.FragmentProfileBinding;
import dev.rstrickland.chat.model.MediaModels;
import dev.rstrickland.chat.model.Profile;
import dev.rstrickland.chat.net.ApiClient;
import dev.rstrickland.chat.net.AvatarLoader;
import dev.rstrickland.chat.net.MediaBlobClient;
import dev.rstrickland.chat.net.TokenStore;
import dev.rstrickland.chat.push.PushRegistrar;
import dev.rstrickland.chat.realtime.RealtimeClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** View + edit the signed-in user's own profile (Phase 2/10 fields), incl. photo avatar. */
public final class ProfileFragment extends Fragment {

    private static final String[] VISIBILITIES = {"PUBLIC", "CONTACTS", "PRIVATE"};
    private static final int MAX_TAGS = 10;
    private static final int MAX_LINKS = 10;
    /** ~90s cap while a freshly-uploaded image is shrunk/thumbnailed (async, like web). */
    private static final int AVATAR_POLL_ATTEMPTS = 60;
    private static final long AVATAR_POLL_INTERVAL_MS = 1500L;

    private FragmentProfileBinding views;
    private ApiClient api;
    private Profile current;

    private ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** System photo picker. Registered as a field per the AndroidX-recommended pattern. */
    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImagePicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        views = FragmentProfileBinding.inflate(inflater, container, false);
        return views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        api = ApiClient.get(requireContext());

        views.visibility.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_list_item_1, VISIBILITIES));

        io = Executors.newSingleThreadExecutor();

        views.saveButton.setOnClickListener(v -> save());
        views.deleteAccountButton.setOnClickListener(v -> confirmDeleteAccount());
        views.avatarContainer.setOnClickListener(v -> pickImage.launch("image/*"));
        view.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in));
        load();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (io != null) io.shutdownNow();
        views = null;
    }

    private void load() {
        views.status.setText("Loading…");
        api.profile().getMine().enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<Profile> call, @NonNull Response<Profile> res) {
                if (views == null) return;
                if (res.body() != null) {
                    current = res.body();
                    bind(current);
                    views.status.setText("");
                } else {
                    views.status.setText("Could not load profile.");
                }
            }

            @Override
            public void onFailure(@NonNull Call<Profile> call, @NonNull Throwable t) {
                if (views != null) views.status.setText("Network error: " + t.getMessage());
            }
        });
    }

    private void bind(Profile p) {
        views.displayName.setText(p.displayName != null ? p.displayName : "");
        views.phone.setText(p.phone != null ? p.phone : "");
        views.bio.setText(p.bio != null ? p.bio : "");
        views.tags.setText(p.tags != null ? String.join(", ", p.tags) : "");
        views.links.setText(p.links != null ? String.join("\n", p.links) : "");
        views.visibility.setText(p.visibility != null ? p.visibility : "PUBLIC", false);
        views.profileAvatar.setText(initials(p.displayName));
        showAvatar(p.avatarMediaId);
    }

    private static String initials(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        return name.trim().substring(0, 1).toUpperCase();
    }

    // ---- avatar photo (display + upload) ----

    /** Resolve the avatar mediaId to a photo and show it over the initials; else show initials. */
    private void showAvatar(@Nullable String avatarMediaId) {
        AvatarLoader.load(api.media(), avatarMediaId, views.profileAvatarImage);
    }

    /** Pick result: upload the chosen image as the new avatar (off the main thread). */
    private void onImagePicked(@Nullable Uri uri) {
        if (uri == null) return;
        if (current == null) {
            setStatus("Still loading your profile — try again in a moment.");
            return;
        }
        final ContentResolver resolver = requireContext().getContentResolver();
        final String contentType = mimeOf(resolver, uri);
        final String userId = current.userId;
        setStatus("Uploading photo…");
        if (io == null) return;

        io.execute(() -> {
            try {
                byte[] bytes = readBytes(resolver, uri);

                // 1. presign, 2. PUT bytes to MinIO, 3. enqueue processing.
                MediaModels.CreateUploadResponse up = body(
                        api.media().createUpload(new MediaModels.CreateUploadRequest(contentType)).execute());
                if (up == null) throw new Exception("could not start upload");
                MediaBlobClient.get().put(up.uploadUrl, bytes, contentType);
                api.media().complete(up.mediaId).execute();

                // Persist the avatar on the profile (partial PATCH — other fields untouched).
                api.profile().update(userId, Profile.Update.avatarOnly(up.mediaId)).execute();

                // Wait for the shrink/thumbnail, then load the processed image.
                MediaModels.MediaView view = pollReady(up.mediaId);
                Bitmap bmp = (view != null && view.isReady())
                        ? MediaBlobClient.get().getBitmap(view.displayUrl()) : null;

                main.post(() -> {
                    if (views == null) return;
                    if (current != null) current.avatarMediaId = up.mediaId;
                    if (bmp != null) applyAvatarBitmap(bmp);
                    setStatus(bmp != null ? "Photo updated." : "Photo saved (still processing).");
                });
            } catch (Exception e) {
                main.post(() -> setStatus("Photo upload failed: " + e.getMessage()));
            }
        });
    }

    /** Poll GET /media/{id} until ready/failed (or the cap). Runs on a background thread. */
    private MediaModels.MediaView pollReady(String mediaId) {
        MediaModels.MediaView last = null;
        for (int attempt = 0; attempt < AVATAR_POLL_ATTEMPTS; attempt++) {
            try {
                last = body(api.media().get(mediaId).execute());
                if (last != null && (last.isReady() || last.isFailed())) return last;
                Thread.sleep(AVATAR_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return last;
            } catch (Exception e) {
                return last;
            }
        }
        return last;
    }

    private void applyAvatarBitmap(Bitmap bmp) {
        if (views == null) return;
        views.profileAvatarImage.setImageBitmap(bmp);
        views.profileAvatarImage.setVisibility(View.VISIBLE);
    }

    private void setStatus(String text) {
        if (views != null) views.status.setText(text);
    }

    private static String mimeOf(ContentResolver resolver, Uri uri) {
        String type = resolver.getType(uri);
        return type != null ? type : "image/jpeg";
    }

    private static <T> T body(Response<T> res) {
        return res.isSuccessful() ? res.body() : null;
    }

    private static byte[] readBytes(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("could not open the selected image");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    // ---- account deletion (Phase 12.5) ----

    private void confirmDeleteAccount() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete account")
                .setMessage("Permanently delete your account — profile, contacts, and sign-in. "
                        + "This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> deleteAccount())
                .show();
    }

    private void deleteAccount() {
        views.deleteAccountButton.setEnabled(false);
        views.status.setText("Deleting account…");
        // Unregister this device from push FIRST, and wait for it: the call is
        // bearer-authed and the token dies with the account, so afterwards is too
        // late. Left registered, the row outlives the user and still gets pushed to
        // — Messaging doesn't know the account is gone, so a peer sending into the
        // old conversation would light up this phone. The callback always runs, so
        // a push-service hiccup can't block the deletion.
        PushRegistrar.unregister(requireContext(), this::submitAccountDeletion);
    }

    private void submitAccountDeletion() {
        if (!isAdded() || views == null) return;
        api.auth().deleteAccount().enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> res) {
                if (!isAdded()) return;
                if (res.isSuccessful()) {
                    signOutToLogin(); // token is dead once the account is gone
                } else if (views != null) {
                    views.deleteAccountButton.setEnabled(true);
                    views.status.setText("Could not delete account (" + res.code() + ").");
                }
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                if (views == null) return;
                views.deleteAccountButton.setEnabled(true);
                views.status.setText("Network error: " + t.getMessage());
            }
        });
    }

    /**
     * Tear down the session and return to the login screen, clearing the back stack.
     * No push unregister here — deleteAccount already did it, while the token was
     * still valid.
     */
    private void signOutToLogin() {
        RealtimeClient.get().stop();
        TokenStore.get(requireContext()).clear();
        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        requireActivity().finish();
    }

    private void save() {
        if (current == null) return;
        String display = views.displayName.getText().toString().trim();
        if (display.isEmpty()) {
            views.status.setText("Display name is required.");
            return;
        }
        String phone = views.phone.getText().toString().trim();
        String bio = views.bio.getText().toString().trim();
        String visibility = views.visibility.getText().toString().trim();
        if (visibility.isEmpty()) visibility = "PUBLIC";

        Profile.Update update = new Profile.Update(
                display,
                bio.isEmpty() ? null : bio,
                phone.isEmpty() ? null : phone,
                visibility);
        // Empty text -> empty list, which the server treats as "clear" (not omitted).
        update.tags = splitList(views.tags.getText().toString(), ",", MAX_TAGS);
        update.links = splitList(views.links.getText().toString(), "[\\n,]", MAX_LINKS);

        views.status.setText("Saving…");
        api.profile().update(current.userId, update).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<Profile> call, @NonNull Response<Profile> res) {
                if (views == null) return;
                if (res.isSuccessful() && res.body() != null) {
                    current = res.body();
                    bind(current);
                    views.status.setText("Saved.");
                } else {
                    // Surface the server's reason (e.g. "each link must be an http(s) URL").
                    views.status.setText(serverError(res, "Save failed."));
                }
            }

            @Override
            public void onFailure(@NonNull Call<Profile> call, @NonNull Throwable t) {
                if (views != null) views.status.setText("Network error: " + t.getMessage());
            }
        });
    }

    /** Split on {@code sepRegex}, trim, drop blanks, dedupe, cap at {@code max} — for tags/links. */
    private static List<String> splitList(String text, String sepRegex, int max) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split(sepRegex)) {
            String item = raw.trim();
            if (!item.isEmpty() && !out.contains(item)) out.add(item);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** Pull the server's {"error": ...} message from a failed response, else {@code fallback}. */
    private static String serverError(Response<?> res, String fallback) {
        try {
            if (res.errorBody() != null) {
                String msg = new JSONObject(res.errorBody().string()).optString("error", null);
                if (msg != null && !msg.isEmpty()) return msg;
            }
        } catch (Exception ignored) {
            /* fall through */
        }
        return fallback;
    }

}
