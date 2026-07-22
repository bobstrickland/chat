/**
 * IdentityProvider — the contract every auth backend must implement.
 *
 * core/ only ever calls methods on this interface. Never import
 * CognitoProvider or LocalAuthProvider directly from core/ — the active
 * implementation is selected once, in config.js, based on AUTH_PROVIDER.
 *
 * All methods return plain objects (no AWS SDK types, no provider-specific
 * shapes) so core/ stays provider-agnostic. Claim/token shapes are
 * normalized here, at the provider boundary — see normalizeClaims below.
 */
export class IdentityProvider {
  /**
   * @param {{ email: string, password: string }} input
   * @returns {Promise<{ userId: string, email: string }>}
   */
  async register(input) {
    throw new Error("not implemented");
  }

  /**
   * @param {{ email: string, password: string }} input
   * @returns {Promise<{
   *   accessToken: string,
   *   idToken: string,
   *   refreshToken: string,
   *   mfaRequired: boolean,
   *   mfaSession?: string
   * }>}
   */
  async authenticate(input) {
    throw new Error("not implemented");
  }

  /**
   * @param {{ refreshToken: string }} input
   * @returns {Promise<{ accessToken: string, idToken: string }>}
   */
  async refreshToken(input) {
    throw new Error("not implemented");
  }

  /**
   * @param {{ accessToken: string }} input
   * @returns {Promise<{ secretCode: string, otpauthUrl: string }>}
   */
  async enrollMfa(input) {
    throw new Error("not implemented");
  }

  /**
   * @param {{ accessToken: string, code: string, mfaSession?: string, email?: string }} input
   * @returns {Promise<{ verified: boolean, accessToken?: string, idToken?: string, refreshToken?: string }>}
   */
  async verifyMfa(input) {
    throw new Error("not implemented");
  }

  /**
   * @param {{ provider: 'google'|'apple', code: string, redirectUri: string }} input
   * @returns {Promise<{ accessToken: string, idToken: string, refreshToken: string }>}
   */
  async federatedLogin(input) {
    throw new Error("not implemented");
  }

  /**
   * Verifies a bearer token and returns normalized claims.
   * Never leak provider-specific claim names (e.g. Cognito's
   * "cognito:username") past this boundary — return the app's own shape.
   *
   * @param {{ token: string }} input
   * @returns {Promise<{ userId: string, email: string, tokenUse: 'access'|'id' }>}
   */
  async verifyToken(input) {
    throw new Error("not implemented");
  }
}
