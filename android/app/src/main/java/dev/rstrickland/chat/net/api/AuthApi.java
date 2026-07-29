package dev.rstrickland.chat.net.api;

import dev.rstrickland.chat.model.AuthModels;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.POST;

/** Auth service (port 3001). Public endpoints — no bearer required, except where noted. */
public interface AuthApi {

    @POST("auth/register")
    Call<AuthModels.LoginResult> register(@Body AuthModels.RegisterRequest body);

    @POST("auth/login")
    Call<AuthModels.LoginResult> login(@Body AuthModels.LoginRequest body);

    @POST("auth/mfa/verify")
    Call<AuthModels.MfaVerifyResult> verifyMfa(@Body AuthModels.MfaVerifyRequest body);

    @POST("auth/refresh")
    Call<AuthModels.RefreshResult> refresh(@Body AuthModels.RefreshRequest body);

    /** Google (Hosted UI) code exchange — returns tokens like login. */
    @POST("auth/federated")
    Call<AuthModels.LoginResult> federated(@Body AuthModels.FederatedRequest body);

    /**
     * Permanently delete the signed-in user's account (Phase 12.5). Bearer-authed
     * (the interceptor attaches the token); the token IS the authorization.
     */
    @DELETE("auth/account")
    Call<Void> deleteAccount();
}
