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
 * Resolves a userId to display info for the UI — the Android counterpart to the
 * web's NamesService. Caches each peer's basic identity (fetched once) and hands
 * back the display name and, where a caller needs it, the avatar mediaId.
 *
 * HARD RULE (per product requirement): this NEVER returns a user identifier as a
 * name. Order: display name -> a neutral placeholder ("…" while loading, "Unknown"
 * if it can't resolve). The raw userId is never shown, not even transiently.
 */
public final class NameResolver {

    public static final String LOADING = "…";
    public static final String UNKNOWN = "Unknown";

    private static NameResolver instance;

    private final ProfileApi profileApi;
    /** One cached profile per userId — serves both name and avatar lookups. */
    private final Map<String, Profile> cache = new ConcurrentHashMap<>();

    public interface Callback {
        void onName(String name);
    }

    /** Delivers a peer's display name AND avatar mediaId (null if none) together. */
    public interface IdentityCallback {
        void onIdentity(String name, String avatarMediaId);
    }

    private NameResolver(Context ctx) {
        this.profileApi = ApiClient.get(ctx).profile();
    }

    public static synchronized NameResolver get(Context ctx) {
        if (instance == null) instance = new NameResolver(ctx.getApplicationContext());
        return instance;
    }

    /** Cached display name if known, else null — callers should still call resolve() to fetch. */
    public String cached(String userId) {
        Profile p = cache.get(userId);
        return p != null ? displayNameOf(p) : null;
    }

    /**
     * Deliver a display label for userId (name only). Same contract as before:
     * a cache hit calls back once with the name; a miss calls back with LOADING
     * now and again with the resolved name. May fire twice — callers guard against
     * view recycling.
     */
    public void resolve(String userId, @NonNull Callback cb) {
        resolveIdentity(userId, (name, avatarMediaId) -> cb.onName(name));
    }

    /**
     * Resolve a peer's display name AND avatar mediaId from a SINGLE profile fetch.
     * On a cache miss the callback fires with {@code (LOADING, null)} first, then
     * the resolved values (Retrofit's enqueue callback runs on the main thread).
     */
    public void resolveIdentity(String userId, @NonNull IdentityCallback cb) {
        if (userId == null || userId.isEmpty()) {
            cb.onIdentity(UNKNOWN, null);
            return;
        }
        Profile hit = cache.get(userId);
        if (hit != null) {
            cb.onIdentity(nameOrUnknown(hit), avatarOf(hit));
            return;
        }
        cb.onIdentity(LOADING, null);
        profileApi.get(userId).enqueue(new retrofit2.Callback<Profile>() {
            @Override
            public void onResponse(@NonNull Call<Profile> call, @NonNull Response<Profile> res) {
                Profile p = res.body();
                if (p != null) {
                    cache.put(userId, p);
                    cb.onIdentity(nameOrUnknown(p), avatarOf(p));
                } else {
                    cb.onIdentity(UNKNOWN, null); // never the id
                }
            }

            @Override
            public void onFailure(@NonNull Call<Profile> call, @NonNull Throwable t) {
                cb.onIdentity(UNKNOWN, null); // never the id; don't cache a failure
            }
        });
    }

    private static String nameOrUnknown(Profile p) {
        String name = displayNameOf(p);
        return name != null ? name : UNKNOWN;
    }

    private static String avatarOf(Profile p) {
        return (p != null && p.avatarMediaId != null && !p.avatarMediaId.trim().isEmpty())
                ? p.avatarMediaId.trim() : null;
    }

    private static String displayNameOf(Profile p) {
        if (p == null || p.displayName == null || p.displayName.trim().isEmpty()) return null;
        return p.displayName.trim();
    }
}
