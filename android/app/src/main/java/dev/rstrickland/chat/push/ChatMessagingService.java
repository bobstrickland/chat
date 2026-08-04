package dev.rstrickland.chat.push;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

import dev.rstrickland.chat.R;

/**
 * Receives FCM pushes and token rotations.
 *
 * When the app is BACKGROUNDED or KILLED, Play services draws the tray
 * notification itself from the push's `notification` block and this class never
 * runs — which is the point: offline push has to work when our process doesn't
 * exist. This class covers the two cases the SDK can't:
 *
 *   - app in the FOREGROUND: onMessageReceived fires instead of a tray
 *     notification, so we post one ourselves from the `data` block;
 *   - token ROTATION: onNewToken fires (restore to a new device, cleared data,
 *     token invalidated) and the server's stored token must be replaced or push
 *     silently stops working.
 */
public final class ChatMessagingService extends FirebaseMessagingService {
    private static final String TAG = "push";

    @Override
    public void onNewToken(@NonNull String token) {
        // May fire while signed out (Firebase mints a token per install, not per
        // user). PushRegistrar.submit no-ops without a session, and MainActivity
        // re-registers on the next sign-in, so nothing is lost.
        PushRegistrar.submit(this, token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        String conversationId = data.get("conversationId");

        // Prefer the notification block's copy (what the tray would have shown),
        // fall back to data for a data-only push.
        String title = null;
        String body = null;
        if (message.getNotification() != null) {
            title = message.getNotification().getTitle();
            body = message.getNotification().getBody();
        }
        if (body == null) body = data.get("body");
        if (body == null || body.isEmpty()) body = getString(R.string.push_default_body);

        Log.i(TAG, "push received for conversation=" + conversationId);
        Notifications.showMessage(this, title, body, conversationId);
    }
}
