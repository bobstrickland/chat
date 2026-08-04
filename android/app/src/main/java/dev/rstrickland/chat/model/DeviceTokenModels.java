package dev.rstrickland.chat.model;

/**
 * Device-token shapes for the Notification service (`POST /device-tokens`).
 *
 * The SAME endpoint and body the web client posts (Client Contract) — only the
 * `subscription` differs per platform: a browser PushSubscription for web, an FCM
 * registration token for android. The server's `platform` attribute is what picks
 * the send mechanism.
 */
public final class DeviceTokenModels {
    private DeviceTokenModels() {}

    /** POST /device-tokens body. */
    public static final class Registration {
        public String deviceId;
        public String platform;
        public Subscription subscription;

        public Registration(String deviceId, String platform, Subscription subscription) {
            this.deviceId = deviceId;
            this.platform = platform;
            this.subscription = subscription;
        }
    }

    /** For android this is just the FCM registration token. */
    public static final class Subscription {
        public String token;

        public Subscription(String token) {
            this.token = token;
        }
    }

    /** 201 response: { userId, deviceId, platform }. */
    public static final class Registered {
        public String userId;
        public String deviceId;
        public String platform;
    }
}
