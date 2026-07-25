package dev.rstrickland.chat.net;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.rstrickland.chat.model.Profile;
import dev.rstrickland.chat.net.api.ProfileApi;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Resolves a userId to a human label for display — the Android counterpart to
 * the web's NamesService. Cached; fetches the profile's display name on demand.
 *
 * HARD RULE (per product requirement): this NEVER returns a user identifier.
 * Order: display name -> (email is not exposed for other users) -> a neutral
 * placeholder ("…" while loading, "Unknown" if it can't resolve). The raw
 * userId is never shown, not even transiently.
 */
public final class NameResolver {

    public static final String LOADING = "…";
    public static final String UNKNOWN = "Unknown";

    private static NameResolver instance;

    private final ProfileApi profileApi;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public interface Callback {
        void onName(String name);
    }

    private NameResolver(Context ctx) {
        this.profileApi = ApiClient.get(ctx).profile();
    }

    public static synchronized NameResolver get(Context ctx) {
        if (instance == null) instance = new NameResolver(ctx.getApplicationContext());
        return instance;
    }

    /** Cached name if known, else null — callers should still call resolve() to fetch. */
    public String cached(String userId) {
        return cache.get(userId);
    }

    /**
     * Deliver a display label for userId. Synchronous cache hit calls back
     * immediately; otherwise calls back with LOADING now and again with the
     * resolved name (Retrofit's enqueue callback runs on the main thread). The
     * callback may fire twice — callers guard against view recycling.
     */
    public void resolve(String userId, @NonNull Callback cb) {
        if (userId == null || userId.isEmpty()) {
            cb.onName(UNKNOWN);
            return;
        }
        String hit = cache.get(userId);
        if (hit != null) {
            cb.onName(hit);
            return;
        }
        cb.onName(LOADING);
        profileApi.get(userId).enqueue(new retrofit2.Callback<Profile>() {
            @Override
            public void onResponse(@NonNull Call<Profile> call, @NonNull Response<Profile> res) {
                String name = displayNameOf(res.body());
                if (name != null) {
                    cache.put(userId, name);
                    cb.onName(name);
                } else {
                    cb.onName(UNKNOWN);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Profile> call, @NonNull Throwable t) {
                cb.onName(UNKNOWN); // never the id; don't cache a failure
            }
        });
    }

    private static String displayNameOf(Profile p) {
        if (p == null || p.displayName == null || p.displayName.trim().isEmpty()) return null;
        return p.displayName.trim();
    }
}
