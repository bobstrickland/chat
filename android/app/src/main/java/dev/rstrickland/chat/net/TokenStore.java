package dev.rstrickland.chat.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

/**
 * Persists the session's tokens (SharedPreferences) so a relaunch stays signed
 * in — the Android counterpart to the web's TokenStore/localStorage.
 *
 * Dev-only storage note: SharedPreferences is app-private but not encrypted; a
 * real build should use EncryptedSharedPreferences. Flagged, not solved.
 */
public final class TokenStore {
    private static final String PREFS = "chat.tokens";
    private static final String K_ACCESS = "accessToken";
    private static final String K_ID = "idToken";
    private static final String K_REFRESH = "refreshToken";

    private static TokenStore instance;
    private final SharedPreferences prefs;

    private TokenStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized TokenStore get(Context ctx) {
        if (instance == null) instance = new TokenStore(ctx);
        return instance;
    }

    public void save(String accessToken, String idToken, String refreshToken) {
        prefs.edit()
                .putString(K_ACCESS, accessToken)
                .putString(K_ID, idToken)
                .putString(K_REFRESH, refreshToken)
                .apply();
    }

    /** After a refresh only the access/id tokens rotate; keep the refresh token. */
    public void updateAccess(String accessToken, String idToken) {
        prefs.edit().putString(K_ACCESS, accessToken).putString(K_ID, idToken).apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    public String accessToken() {
        return prefs.getString(K_ACCESS, null);
    }

    public String refreshToken() {
        return prefs.getString(K_REFRESH, null);
    }

    public boolean isAuthenticated() {
        return accessToken() != null;
    }

    /** The signed-in user's id (sub), decoded from the access token — client-side only. */
    public String userId() {
        return claim("sub");
    }

    /**
     * The signed-in user's email, decoded from the ID token (the access token
     * may not carry it, e.g. federated). Used only as a display fallback when the
     * user has no display name — the app must never fall back to the raw userId.
     */
    public String email() {
        return claimFrom(prefs.getString(K_ID, null), "email");
    }

    /**
     * Whether this session can self-manage TOTP MFA (email/password logins carry
     * the aws.cognito.signin.user.admin scope; federated logins don't) — mirrors
     * the web TokenStore.canManageMfa. Used to hide 2FA for Google sessions later.
     */
    public boolean canManageMfa() {
        String scope = claim("scope");
        return scope != null && scope.contains("aws.cognito.signin.user.admin");
    }

    private String claim(String name) {
        return claimFrom(accessToken(), name);
    }

    private String claimFrom(String token, String name) {
        if (token == null) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            byte[] json = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            JSONObject claims = new JSONObject(new String(json, "UTF-8"));
            String v = claims.optString(name, null);
            return (v == null || v.isEmpty()) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }
}
