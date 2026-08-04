package dev.rstrickland.chat.push;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.UUID;

import dev.rstrickland.chat.model.DeviceTokenModels;
import dev.rstrickland.chat.net.ApiClient;
import dev.rstrickland.chat.net.TokenStore;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Registers this device with the Notification service so it can be pushed to when
 * the app has no live WebSocket — the Android counterpart to the web client's
 * PushService (same endpoint, {@code POST /device-tokens}, per the Client
 * Contract; only the mechanism differs: an FCM registration token instead of a
 * browser PushSubscription).
 *
 * Entirely best-effort, exactly like the web: no Firebase config, no Play
 * services, a denied permission or a failed call all mean "no offline push" —
 * never a broken app. Live messages keep arriving over the socket regardless.
 */
public final class PushRegistrar {
    private static final String TAG = "push";
    private static final String PREFS = "chat.push";
    private static final String K_DEVICE_ID = "deviceId";

    private PushRegistrar() {}

    /**
     * True when Firebase actually initialized — i.e. app/google-services.json was
     * present at build time. Without it FirebaseApp has no default instance and
     * every FirebaseMessaging call would throw, so everything here checks first.
     */
    public static boolean isAvailable(Context ctx) {
        try {
            return !FirebaseApp.getApps(ctx).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Fetch this install's FCM token and register it. Safe to call repeatedly. */
    public static void register(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!TokenStore.get(app).isAuthenticated()) {
            return; // registration is per-user; it happens again after sign-in
        }
        if (!isAvailable(app)) {
            Log.i(TAG, "Firebase not configured (no google-services.json) — offline push disabled");
            return;
        }
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) {
                    Log.w(TAG, "FCM token unavailable", task.getException());
                    return;
                }
                submit(app, task.getResult());
            });
        } catch (Exception e) {
            Log.w(TAG, "FCM token request failed: " + e.getMessage());
        }
    }

    /**
     * POST the token as this device's subscription. Upserts on {@code deviceId},
     * so re-registering (new token, re-login, app restart) replaces the row rather
     * than accumulating stale tokens for the user.
     */
    public static void submit(Context ctx, @Nullable String fcmToken) {
        if (fcmToken == null || fcmToken.isEmpty()) return;
        Context app = ctx.getApplicationContext();
        if (!TokenStore.get(app).isAuthenticated()) return;

        DeviceTokenModels.Registration body = new DeviceTokenModels.Registration(
                deviceId(app), "android", new DeviceTokenModels.Subscription(fcmToken));

        ApiClient.get(app).notification().registerDevice(body).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<DeviceTokenModels.Registered> call,
                                   @NonNull Response<DeviceTokenModels.Registered> res) {
                if (res.isSuccessful()) {
                    Log.i(TAG, "device registered for push");
                } else {
                    Log.w(TAG, "device registration rejected: HTTP " + res.code());
                }
            }

            @Override
            public void onFailure(@NonNull Call<DeviceTokenModels.Registered> call,
                                 @NonNull Throwable t) {
                Log.w(TAG, "device registration failed: " + t.getMessage());
            }
        });
    }

    /**
     * Drop this device's registration, then run {@code after}.
     *
     * Called on sign-out, and the reason it takes a callback: the DELETE is
     * bearer-authed, so it has to complete BEFORE the tokens are cleared. Without
     * it the row survives sign-out and the next user of this phone would receive
     * the previous user's offline pushes.
     *
     * Sign-out must never be blocked by the network, so a failure (or no session,
     * or no Firebase) still runs {@code after} — worst case a stale row lingers
     * until that token dies and gets pruned.
     */
    public static void unregister(Context ctx, @NonNull Runnable after) {
        Context app = ctx.getApplicationContext();
        if (!TokenStore.get(app).isAuthenticated()) {
            after.run();
            return;
        }
        ApiClient.get(app).notification().unregisterDevice(deviceId(app)).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> res) {
                after.run();
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                Log.w(TAG, "device unregistration failed: " + t.getMessage());
                after.run();
            }
        });
    }

    /**
     * Stable per-install id — the SK of the device-tokens row. The FCM token itself
     * rotates (restore to a new device, app data cleared), so keying on it would
     * leave orphan rows; this id survives token rotation, matching how the web
     * keeps its deviceId in localStorage.
     */
    public static String deviceId(Context ctx) {
        SharedPreferences prefs =
                ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(K_DEVICE_ID, null);
        if (id == null) {
            id = "android-" + UUID.randomUUID();
            prefs.edit().putString(K_DEVICE_ID, id).apply();
        }
        return id;
    }
}
