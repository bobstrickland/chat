import {
  SignUpCommand,
  InitiateAuthCommand,
  RespondToAuthChallengeCommand,
  AssociateSoftwareTokenCommand,
  VerifySoftwareTokenCommand,
  SetUserMFAPreferenceCommand,
  GetTokensFromRefreshTokenCommand,
} from "@aws-sdk/client-cognito-identity-provider";
import { IdentityProvider } from "./IdentityProvider.js";
import { verifyCognitoToken } from "../clients/jwksVerifier.js";

export class CognitoProvider extends IdentityProvider {
  /**
   * @param {import('@aws-sdk/client-cognito-identity-provider').CognitoIdentityProviderClient} client
   * @param {{ userPoolId: string, clientId: string, region: string, jwksUrl: string }} config
   */
  constructor(client, config) {
    super();
    this.client = client;
    this.config = config;
  }

  async register({ email, password }) {
    const result = await this.client.send(
      new SignUpCommand({
        ClientId: this.config.clientId,
        Username: email,
        Password: password,
        UserAttributes: [{ Name: "email", Value: email }],
      })
    );

    return { userId: result.UserSub, email };
  }

  async authenticate({ email, password }) {
    const result = await this.client.send(
      new InitiateAuthCommand({
        AuthFlow: "USER_PASSWORD_AUTH",
        ClientId: this.config.clientId,
        AuthParameters: { USERNAME: email, PASSWORD: password },
      })
    );

    if (result.ChallengeName === "SOFTWARE_TOKEN_MFA") {
      return {
        mfaRequired: true,
        mfaSession: result.Session,
        accessToken: null,
        idToken: null,
        refreshToken: null,
      };
    }

    return {
      mfaRequired: false,
      accessToken: result.AuthenticationResult.AccessToken,
      idToken: result.AuthenticationResult.IdToken,
      refreshToken: result.AuthenticationResult.RefreshToken,
    };
  }

  async refreshToken({ refreshToken }) {
    const result = await this.client.send(
      new GetTokensFromRefreshTokenCommand({
        ClientId: this.config.clientId,
        RefreshToken: refreshToken,
      })
    );

    return {
      accessToken: result.AuthenticationResult.AccessToken,
      idToken: result.AuthenticationResult.IdToken,
    };
  }

  async enrollMfa({ accessToken }) {
    const result = await this.client.send(
      new AssociateSoftwareTokenCommand({ AccessToken: accessToken })
    );

    const otpauthUrl = `otpauth://totp/ChatApp?secret=${result.SecretCode}&issuer=ChatApp`;
    return { secretCode: result.SecretCode, otpauthUrl };
  }

  async verifyMfa({ accessToken, code, mfaSession, email }) {
    // Post-enrollment verification (user has an access token already)
    if (accessToken) {
      await this.client.send(
        new VerifySoftwareTokenCommand({ AccessToken: accessToken, UserCode: code })
      );
      await this.client.send(
        new SetUserMFAPreferenceCommand({
          AccessToken: accessToken,
          SoftwareTokenMfaSettings: { Enabled: true, PreferredMfa: true },
        })
      );
      return { verified: true };
    }

    // Login-time MFA challenge response (no access token yet, have a session)
    const result = await this.client.send(
      new RespondToAuthChallengeCommand({
        ClientId: this.config.clientId,
        ChallengeName: "SOFTWARE_TOKEN_MFA",
        Session: mfaSession,
        ChallengeResponses: { USERNAME: email, SOFTWARE_TOKEN_MFA_CODE: code },
      })
    );

    return {
      verified: true,
      accessToken: result.AuthenticationResult.AccessToken,
      idToken: result.AuthenticationResult.IdToken,
      refreshToken: result.AuthenticationResult.RefreshToken,
    };
  }

  async federatedLogin({ provider, code, redirectUri }) {
    // Cognito Hosted UI handles the OAuth2 code exchange server-side via its
    // /oauth2/token endpoint. This wraps that HTTP call — not a first-class
    // SDK command like the others.
    const tokenUrl = `https://${this.config.hostedUiDomain}/oauth2/token`;
    const body = new URLSearchParams({
      grant_type: "authorization_code",
      client_id: this.config.clientId,
      code,
      redirect_uri: redirectUri,
    });

    const res = await fetch(tokenUrl, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });

    if (!res.ok) {
      throw new Error(`federated token exchange failed: ${res.status}`);
    }

    const tokens = await res.json();
    return {
      accessToken: tokens.access_token,
      idToken: tokens.id_token,
      refreshToken: tokens.refresh_token,
    };
  }

  async verifyToken({ token }) {
    // Normalizes Cognito's claim shape (cognito:username, token_use, etc.)
    // into the app's own shape — nothing downstream should ever see raw
    // Cognito claims.
    const claims = await verifyCognitoToken(token, this.config.jwksUrl);

    return {
      userId: claims.sub,
      email: claims.email ?? null,
      tokenUse: claims.token_use === "id" ? "id" : "access",
    };
  }
}
