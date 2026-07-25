package dev.rstrickland.chat.net;

import android.content.Context;

import dev.rstrickland.chat.net.api.AuthApi;
import dev.rstrickland.chat.net.api.MessagingApi;
import dev.rstrickland.chat.net.api.ProfileApi;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Builds one shared OkHttp client (auth header + logging) and a Retrofit
 * instance per service base URL — because there's no single gateway locally
 * (see ApiConfig). All service APIs are reachable via the accessors here.
 *
 * The config.js / ApiClient analogue: one wiring point, injected not hardcoded.
 */
public final class ApiClient {
    private static ApiClient instance;

    private final AuthApi authApi;
    private final ProfileApi profileApi;
    private final MessagingApi messagingApi;

    private ApiClient(Context ctx) {
        TokenStore tokens = TokenStore.get(ctx);

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(new AuthInterceptor(tokens))
                .addInterceptor(logging)
                .build();

        this.authApi = retrofit(http, ApiConfig.AUTH).create(AuthApi.class);
        this.profileApi = retrofit(http, ApiConfig.PROFILE).create(ProfileApi.class);
        this.messagingApi = retrofit(http, ApiConfig.MESSAGING).create(MessagingApi.class);
    }

    private static Retrofit retrofit(OkHttpClient http, String baseUrl) {
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
    }

    public static synchronized ApiClient get(Context ctx) {
        if (instance == null) instance = new ApiClient(ctx);
        return instance;
    }

    public AuthApi auth() {
        return authApi;
    }

    public ProfileApi profile() {
        return profileApi;
    }

    public MessagingApi messaging() {
        return messagingApi;
    }
}
