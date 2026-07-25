package dev.rstrickland.chat.model;

/**
 * Auth request/response shapes — mirror the Auth service contract (the same
 * shapes the web client's models.ts declares). Grouped as nested classes to keep
 * the DTO surface in one file.
 */
public final class AuthModels {
    private AuthModels() {}

    public static final class RegisterRequest {
        public String email;
        public String password;

        public RegisterRequest(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    public static final class LoginRequest {
        public String email;
        public String password;

        public LoginRequest(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    /** POST /auth/login result. Either tokens, or an MFA challenge to answer. */
    public static final class LoginResult {
        public boolean mfaRequired;
        public String mfaSession;
        public String accessToken;
        public String idToken;
        public String refreshToken;
    }

    /** POST /auth/mfa/verify — the login-challenge branch. */
    public static final class MfaVerifyRequest {
        public String email;
        public String code;
        public String mfaSession;

        public MfaVerifyRequest(String email, String code, String mfaSession) {
            this.email = email;
            this.code = code;
            this.mfaSession = mfaSession;
        }
    }

    public static final class MfaVerifyResult {
        public boolean verified;
        public String accessToken;
        public String idToken;
        public String refreshToken;
    }

    public static final class RefreshRequest {
        public String refreshToken;

        public RefreshRequest(String refreshToken) {
            this.refreshToken = refreshToken;
        }
    }

    public static final class RefreshResult {
        public String accessToken;
        public String idToken;
    }
}
