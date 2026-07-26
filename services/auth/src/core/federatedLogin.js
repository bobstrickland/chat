const SUPPORTED_PROVIDERS = ["google", "apple"];
const SUPPORTED_CLIENTS = ["web", "mobile"];

/**
 * @param {{ identityProvider: import('../providers/IdentityProvider.js').IdentityProvider }} deps
 * @param {{ provider: string, code: string, redirectUri: string, client?: string }} input
 *   `client` selects which Cognito app client to exchange the code with
 *   ("web" default, or "mobile" for the Android Hosted-UI flow).
 */
export async function federatedLogin({ identityProvider }, input) {
  if (!SUPPORTED_PROVIDERS.includes(input.provider)) {
    throw new Error(`unsupported provider: ${input.provider}`);
  }
  if (input.client && !SUPPORTED_CLIENTS.includes(input.client)) {
    throw new Error(`unsupported client: ${input.client}`);
  }
  if (!input.code || !input.redirectUri) {
    throw new Error("code and redirectUri are required");
  }

  return identityProvider.federatedLogin(input);
}
