package dev.rstrickland.chat.net;

import androidx.annotation.NonNull;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Attaches the bearer token to every request (the Android analogue of the web
 * auth interceptor). The Auth endpoints (register/login/refresh) are public, so
 * we only add the header when a token exists.
 */
public final class AuthInterceptor implements Interceptor {
    private final TokenStore tokens;

    public AuthInterceptor(TokenStore tokens) {
        this.tokens = tokens;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request original = chain.request();
        String token = tokens.accessToken();
        if (token == null) {
            return chain.proceed(original);
        }
        Request authed = original.newBuilder()
                .header("Authorization", "Bearer " + token)
                .build();
        return chain.proceed(authed);
    }
}
