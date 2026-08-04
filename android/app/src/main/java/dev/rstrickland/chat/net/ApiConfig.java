package dev.rstrickland.chat.net;

import dev.rstrickland.chat.BuildConfig;

/**
 * Backend endpoints — ONE host, path-routed, the same shape as the deployed API.
 *
 * This used to be seven {@code host:port} constants (`:3001`…`:3007`) because
 * local dev had no gateway. That topology existed nowhere but a developer's
 * machine, and it made the client encode which service owns which path. There is
 * now a local nginx gateway (`local-dev/gateway/`, host port 8080) mirroring the
 * web client's `proxy.conf.json`, so every environment presents a single base URL
 * and the Retrofit paths ({@code auth/login}, {@code profiles/me}, …) resolve
 * against it unchanged.
 *
 * Values come from {@code BuildConfig}, set per build type in
 * {@code app/build.gradle}:
 *
 * <ul>
 *   <li><b>debug</b> — {@code http://10.0.2.2:8080/} (the emulator's alias for the
 *       host machine) and {@code ws://10.0.2.2:8080/ws}
 *   <li><b>release</b> — the deployed {@code https://} / {@code wss://} hostnames
 * </ul>
 *
 * Switching environments is therefore a build-type change, not a source edit. For
 * a REAL DEVICE on the same LAN, override the debug values in build.gradle with
 * the machine's LAN IP — {@code 10.0.2.2} is emulator-only.
 */
public final class ApiConfig {
    private ApiConfig() {}

    /** Single REST base. The trailing slash matters — Retrofit resolves against it. */
    public static final String API_BASE = BuildConfig.API_BASE_URL;

    /** WebSocket endpoint (ws-shim locally, API Gateway WS deployed). Token in the query. */
    public static final String WS = BuildConfig.WS_URL;

    // ---- Cognito Hosted UI (Google sign-in via Custom Tabs) ----
    // Public values — they ship in every APK. The Google credentials live inside
    // Cognito, not here.
    public static final String HOSTED_UI_DOMAIN = BuildConfig.HOSTED_UI_DOMAIN;
    public static final String COGNITO_MOBILE_CLIENT_ID = BuildConfig.COGNITO_MOBILE_CLIENT_ID;
    public static final String OAUTH_REDIRECT = BuildConfig.OAUTH_REDIRECT;

    /**
     * DEV ONLY, and empty in a release build: what {@code localhost} should
     * resolve to when fetching MinIO presigned URLs (see {@link DevMediaDns} for
     * why the URL itself can't be rewritten). Deliberately NOT the gateway —
     * media bytes go straight to object storage, never through it.
     */
    public static final String DEV_BLOB_HOST = BuildConfig.DEV_BLOB_HOST;
}
