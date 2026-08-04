package dev.rstrickland.chat.net.api;

import dev.rstrickland.chat.model.DeviceTokenModels;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Notification service (port 3005) — device registration for offline push.
 * Bearer-authed by the shared interceptor: a user can only register their own
 * device.
 *
 * `GET /push/config` is deliberately absent: it serves the VAPID public key,
 * which only the browser needs. Android's credentials live in
 * google-services.json, and its sender key lives server-side in the Firebase
 * service account.
 */
public interface NotificationApi {

    @POST("device-tokens")
    Call<DeviceTokenModels.Registered> registerDevice(@Body DeviceTokenModels.Registration body);

    /** Sign-out: stop pushing this user's messages to this device. */
    @DELETE("device-tokens/{deviceId}")
    Call<Void> unregisterDevice(@Path("deviceId") String deviceId);
}
