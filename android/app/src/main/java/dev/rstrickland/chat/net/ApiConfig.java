package dev.rstrickland.chat.net;

/**
 * Backend endpoints. There's no single gateway in local dev (the web client uses
 * a dev proxy), so each service has its own host:port — the same map as the web
 * client's proxy.conf.json. On the Android emulator, {@code 10.0.2.2} is the host
 * machine's loopback, so these reach the docker-compose services running there.
 *
 * For a real device or a deployed environment, point HOST at the API hostname
 * (and switch to https / a single API Gateway) — nothing else here changes.
 */
public final class ApiConfig {
    private ApiConfig() {}

    public static final String HOST = "10.0.2.2"; // emulator -> host machine

    public static final String AUTH = "http://" + HOST + ":3001/";
    public static final String PROFILE = "http://" + HOST + ":3002/"; // profiles + contacts
    public static final String MESSAGING = "http://" + HOST + ":3003/";
    public static final String PRESENCE = "http://" + HOST + ":3004/";
    public static final String NOTIFICATION = "http://" + HOST + ":3005/";
    public static final String MEDIA = "http://" + HOST + ":3006/";
    public static final String SEARCH = "http://" + HOST + ":3007/";

    /** API Gateway WebSocket stand-in (ws-shim). Token rides in the query string. */
    public static final String WS = "ws://" + HOST + ":8090";

    // ---- Cognito Hosted UI (Google sign-in via Custom Tabs) ----
    // These are public values (they ship in every APK): the Hosted UI domain, the
    // Cognito MOBILE app client id, and a redirect URI already registered on that
    // client. The Google credentials live inside Cognito, not here.
    public static final String HOSTED_UI_DOMAIN = "chat-dev-local.auth.us-east-1.amazoncognito.com";
    public static final String COGNITO_MOBILE_CLIENT_ID = "scdkebsivhvab3g2799ljjaon";
    public static final String OAUTH_REDIRECT = "myapp://callback"; // registered mobile callback
}
