package dev.rstrickland.chat.net.api;

import dev.rstrickland.chat.model.Profile;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.PATCH;
import retrofit2.http.Path;

/** Profile service (port 3002). Bearer added by the interceptor. */
public interface ProfileApi {

    @GET("profiles/me")
    Call<Profile> getMine();

    /** Another user's profile (for name resolution). Returns basic identity if restricted. */
    @GET("profiles/{userId}")
    Call<Profile> get(@Path("userId") String userId);

    @PATCH("profiles/{userId}")
    Call<Profile> update(@Path("userId") String userId, @Body Profile.Update body);
}
