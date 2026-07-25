package dev.rstrickland.chat;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import dev.rstrickland.chat.databinding.ActivityLoginBinding;
import dev.rstrickland.chat.model.AuthModels;
import dev.rstrickland.chat.net.ApiClient;
import dev.rstrickland.chat.net.TokenStore;
import dev.rstrickland.chat.realtime.RealtimeClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Email/password sign-in against the Auth service (same endpoints as the web
 * client — Client Contract). Handles the MFA challenge branch inline: a login
 * that returns {@code mfaRequired} reveals the code field and the next tap
 * answers the challenge.
 *
 * Google sign-in (Cognito Hosted UI via Custom Tabs, using the mobile app
 * client) is increment 2 — the button is present but disabled.
 */
public final class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding views;
    private TokenStore tokens;
    private ApiClient api;

    private String pendingEmail;
    private String mfaSession; // non-null once we're answering an MFA challenge

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tokens = TokenStore.get(this);
        api = ApiClient.get(this);

        if (tokens.isAuthenticated()) {
            goToMain();
            return;
        }

        views = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(views.getRoot());

        views.loginContent.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in));
        views.loginButton.setOnClickListener(v -> submit());
    }

    private void submit() {
        views.status.setText("");
        if (mfaSession != null) {
            verifyMfa();
        } else {
            login();
        }
    }

    private void login() {
        String email = views.email.getText().toString().trim();
        String password = views.password.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            views.status.setText("Enter your email and password.");
            return;
        }
        setBusy(true);
        api.auth().login(new AuthModels.LoginRequest(email, password)).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<AuthModels.LoginResult> call,
                                   @NonNull Response<AuthModels.LoginResult> res) {
                setBusy(false);
                if (!res.isSuccessful() || res.body() == null) {
                    views.status.setText("Sign in failed. Check your credentials.");
                    return;
                }
                AuthModels.LoginResult r = res.body();
                if (r.mfaRequired) {
                    pendingEmail = email;
                    mfaSession = r.mfaSession;
                    views.mfaLayout.setVisibility(View.VISIBLE);
                    views.mfaLayout.startAnimation(AnimationUtils.loadAnimation(LoginActivity.this, R.anim.fade_in));
                    views.passwordLayout.setEnabled(false);
                    views.loginButton.setText("Verify code");
                    views.status.setText("Enter the code from your authenticator app.");
                } else if (r.accessToken != null) {
                    onSignedIn(r.accessToken, r.idToken, r.refreshToken);
                } else {
                    views.status.setText("Unexpected response from server.");
                }
            }

            @Override
            public void onFailure(@NonNull Call<AuthModels.LoginResult> call, @NonNull Throwable t) {
                setBusy(false);
                views.status.setText("Network error: " + t.getMessage());
            }
        });
    }

    private void verifyMfa() {
        String code = views.mfaCode.getText().toString().trim();
        if (code.isEmpty()) {
            views.status.setText("Enter the 6-digit code.");
            return;
        }
        setBusy(true);
        api.auth().verifyMfa(new AuthModels.MfaVerifyRequest(pendingEmail, code, mfaSession))
                .enqueue(new Callback<>() {
                    @Override
                    public void onResponse(@NonNull Call<AuthModels.MfaVerifyResult> call,
                                           @NonNull Response<AuthModels.MfaVerifyResult> res) {
                        setBusy(false);
                        if (res.isSuccessful() && res.body() != null && res.body().accessToken != null) {
                            AuthModels.MfaVerifyResult r = res.body();
                            onSignedIn(r.accessToken, r.idToken, r.refreshToken);
                        } else {
                            views.status.setText("Incorrect or expired code.");
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<AuthModels.MfaVerifyResult> call,
                                          @NonNull Throwable t) {
                        setBusy(false);
                        views.status.setText("Network error: " + t.getMessage());
                    }
                });
    }

    private void onSignedIn(String accessToken, String idToken, String refreshToken) {
        tokens.save(accessToken, idToken, refreshToken);
        RealtimeClient.get().start(accessToken);
        goToMain();
    }

    private void setBusy(boolean busy) {
        views.loginButton.setEnabled(!busy);
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
