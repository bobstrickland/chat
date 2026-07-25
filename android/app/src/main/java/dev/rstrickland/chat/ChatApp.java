package dev.rstrickland.chat;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Application entry point. Kept thin — the singletons (ApiClient, TokenStore,
 * RealtimeClient) build lazily on first use.
 *
 * Material You: {@link DynamicColors#applyToActivitiesIfAvailable} opts every
 * activity into wallpaper-derived dynamic color ON API 31+ WHEN the device
 * supports it; on older APIs (or unsupported devices) it is a no-op and the
 * fixed Teal/Charcoal theme in themes.xml is used instead. So dynamic color is
 * an enhancement, never the only path (the Views equivalent of the
 * dynamicLightColorScheme/darkColorScheme + fallback wiring).
 *
 * Note: this recolors the M3 ROLES (primary/secondary/surface…). The chat
 * bubbles are intentionally FIXED brand colors (@color/bubble_*), so sent
 * bubbles stay teal even under dynamic color — matching the spec's explicit
 * SentBubble/ReceivedBubble values.
 */
public final class ChatApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
