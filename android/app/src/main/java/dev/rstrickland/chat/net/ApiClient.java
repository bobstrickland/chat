package dev.rstrickland.chat.net;

import android.content.Context;

import dev.rstrickland.chat.net.api.AuthApi;
import dev.rstrickland.chat.net.api.ContactsApi;
import dev.rstrickland.chat.net.api.MediaApi;
import dev.rstrickland.chat.net.api.MessagingApi;
import dev.rstrickland.chat.net.api.ProfileApi;
import dev.rstrickland.chat.net.api.SearchApi;
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
    private final ContactsApi contactsApi;
    private final SearchApi searchApi;
    private final MediaApi mediaApi;

    private ApiClient(Context ctx) {
        TokenStore tokens = TokenStore.get(ctx);

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(new AuthInterceptor(tokens))
                .addInterceptor(logging)
                .build();

        this.authApi = retrofit(http, ApiConfig.AUTH).create(AuthApi.class);
        Retrofit profileRetrofit = retrofit(http, ApiConfig.PROFILE);
        this.profileApi = profileRetrofit.create(ProfileApi.class);
        this.contactsApi = profileRetrofit.create(ContactsApi.class); // contacts live on the Profile service
        this.messagingApi = retrofit(http, ApiConfig.MESSAGING).create(MessagingApi.class);
        this.searchApi = retrofit(http, ApiConfig.SEARCH).create(SearchApi.class);
        this.mediaApi = retrofit(http, ApiConfig.MEDIA).create(MediaApi.class);
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
}
