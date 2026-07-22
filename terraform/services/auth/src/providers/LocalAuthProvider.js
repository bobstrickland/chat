import jwt from "jsonwebtoken";
import crypto from "node:crypto";
import { IdentityProvider } from "./IdentityProvider.js";

/**
 * Offline-only stub. Self-signed JWTs, in-memory user store, no MFA,
 * no OAuth2. Never used past local dev — see CLAUDE.md "Local Development".
 * Not a target for feature parity with CognitoProvider.
 */
export class LocalAuthProvider extends IdentityProvider {
  constructor(config) {
    super();
    this.signingSecret = config.signingSecret;
    this.users = new Map(); // email -> { userId, email, password }
  }

  async register({ email, password }) {
    if (this.users.has(email)) {
      throw new Error("user already exists");
    }
    const userId = crypto.randomUUID();
    this.users.set(email, { userId, email, password });
    return { userId, email };
  }

  async authenticate({ email, password }) {
    const user = this.users.get(email);
    if (!user || user.password !== password) {
      throw new Error("invalid credentials");
    }

    const accessToken = this._signToken(user, "access");
    const idToken = this._signToken(user, "id");
    const refreshToken = this._signToken(user, "refresh", "30d");

    return { mfaRequired: false, accessToken, idToken, refreshToken };
  }

  async refreshToken({ refreshToken }) {
    const claims = jwt.verify(refreshToken, this.signingSecret);
    const user = { userId: claims.sub, email: claims.email };
    return {
      accessToken: this._signToken(user, "access"),
      idToken: this._signToken(user, "id"),
    };
  }

  async enrollMfa() {
    throw new Error("MFA not supported by LocalAuthProvider — use AUTH_PROVIDER=cognito");
  }

  async verifyMfa() {
    throw new Error("MFA not supported by LocalAuthProvider — use AUTH_PROVIDER=cognito");
  }

  async federatedLogin() {
    throw new Error("OAuth2 federation not supported by LocalAuthProvider — use AUTH_PROVIDER=cognito");
  }

  async verifyToken({ token }) {
    const claims = jwt.verify(token, this.signingSecret);
    return { userId: claims.sub, email: claims.email, tokenUse: claims.token_use };
  }

  _signToken(user, tokenUse, expiresIn = "1h") {
    return jwt.sign(
      { sub: user.userId, email: user.email, token_use: tokenUse },
      this.signingSecret,
      { expiresIn }
    );
  }
}
