package dev.rstrickland.chat.net;

import android.content.Context;

import dev.rstrickland.chat.net.api.AuthApi;
import dev.rstrickland.chat.net.api.ContactsApi;
import dev.rstrickland.chat.net.api.MediaApi;
import dev.rstrickland.chat.net.api.MessagingApi;
import dev.rstrickland.chat.net.api.NotificationApi;
import dev.rstrickland.chat.net.api.ProfileApi;
import dev.rstrickland.chat.net.api.SearchApi;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Builds one shared OkHttp client (auth header + logging) and ONE Retrofit
 * instance for all of them — every service is behind a single base URL now (see
 * ApiConfig), so the per-service Retrofit instances this used to hold are gone.
 * The interfaces already carry their path prefixes ({@code auth/login},
 * {@code profiles/me}, …), which is why one base resolves them all unchanged.
 *
 * The config.js / ApiClient analogue: one wiring point, injected not hardcoded.
 */
public final class ApiClient {
    private static ApiClient instance;

    private final AuthApi authApi;
    private final ProfileApi profileApi;
    private final MessagingApi messagingApi;
    private final ContactsApi contactsApi;
    private final SearchApi searchApi;
    private final MediaApi mediaApi;
    private final NotificationApi notificationApi;

    private ApiClient(Context ctx) {
        TokenStore tokens = TokenStore.get(ctx);

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(new AuthInterceptor(tokens))
                .addInterceptor(logging)
                .build();

        Retrofit api = retrofit(http, ApiConfig.API_BASE);
        this.authApi = api.create(AuthApi.class);
        this.profileApi = api.create(ProfileApi.class);
        this.contactsApi = api.create(ContactsApi.class); // contacts live on the Profile service
        this.messagingApi = api.create(MessagingApi.class);
        this.searchApi = api.create(SearchApi.class);
        this.mediaApi = api.create(MediaApi.class);
        this.notificationApi = api.create(NotificationApi.class);
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

    public ContactsApi contacts() {
        return contactsApi;
    }

    public SearchApi search() {
        return searchApi;
    }

    public MediaApi media() {
        return mediaApi;
    }

    public NotificationApi notification() {
        return notificationApi;
    }
}
