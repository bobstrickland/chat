package dev.rstrickland.chat.net.api;

import dev.rstrickland.chat.model.AuthModels;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

/** Auth service (port 3001). Public endpoints — no bearer required. */
public interface AuthApi {

    @POST("auth/register")
    Call<AuthModels.LoginResult> register(@Body AuthModels.RegisterRequest body);

    @POST("auth/login")
    Call<AuthModels.LoginResult> login(@Body AuthModels.LoginRequest body);

    @POST("auth/mfa/verify")
    Call<AuthModels.MfaVerifyResult> verifyMfa(@Body AuthModels.MfaVerifyRequest body);

    @POST("auth/refresh")
    Call<AuthModels.RefreshResult> refresh(@Body AuthModels.RefreshRequest body);
}
