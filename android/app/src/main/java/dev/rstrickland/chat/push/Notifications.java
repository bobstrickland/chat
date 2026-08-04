package dev.rstrickland.chat.push;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import dev.rstrickland.chat.MainActivity;
import dev.rstrickland.chat.R;

/**
 * The notification channel and the tray notification itself.
 *
 * The channel id must match what the server sends as FCM's {@code android
 * .notification.channel_id} ({@code FCM_ANDROID_CHANNEL_ID}, default "messages"),
 * because a push whose channel doesn't exist is dropped silently on API 26+.
 * Creating the channel is idempotent, so it's done on every app start.
 */
public final class Notifications {
    public static final String CHANNEL_MESSAGES = "messages";

    /** Extra carried on the tap intent so MainActivity can open the conversation. */
    public static final String EXTRA_CONVERSATION_ID = "conversationId";

    private Notifications() {}

    public static void createChannels(Context ctx) {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_MESSAGES,
                ctx.getString(R.string.channel_messages_name),
                NotificationManager.IMPORTANCE_HIGH); // a chat message pops a heads-up
        channel.setDescription(ctx.getString(R.string.channel_messages_desc));
        channel.setShowBadge(true);
        NotificationManagerCompat.from(ctx).createNotificationChannel(channel);
    }

    /**
     * Posts a "new message" notification. Used for the FOREGROUND case only — when
     * the app is backgrounded or killed, Play services draws the notification from
     * the FCM `notification` block and this never runs.
     *
     * One notification per conversation (the conversation id is the tag), so a
     * chatty group replaces its own notification instead of stacking twenty.
     */
    public static void showMessage(Context ctx, String title, String body, String conversationId) {
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (conversationId != null && !conversationId.isEmpty()) {
            open.putExtra(EXTRA_CONVERSATION_ID, conversationId);
        }
        PendingIntent tap = PendingIntent.getActivity(
                ctx,
                conversationId != null ? conversationId.hashCode() : 0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle(title != null ? title : ctx.getString(R.string.push_default_title))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(tap)
                .build();

        try {
            NotificationManagerCompat.from(ctx)
                    .notify(conversationId != null ? conversationId : "chat", 1, notification);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted (API 33+). Nothing to do — the user
            // declined notifications; the message is still in the chat.
        }
    }
}
