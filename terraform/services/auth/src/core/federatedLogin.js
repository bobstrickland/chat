const SUPPORTED_PROVIDERS = ["google", "apple"];

/**
 * @param {import('../providers/IdentityProvider.js').IdentityProvider} identityProvider
 * @param {{ provider: string, code: string, redirectUri: string }} input
 */
export async function federatedLogin(identityProvider, input) {
  if (!SUPPORTED_PROVIDERS.includes(input.provider)) {
    throw new Error(`unsupported provider: ${input.provider}`);
  }
  if (!input.code || !input.redirectUri) {
    throw new Error("code and redirectUri are required");
  }

  return identityProvider.federatedLogin(input);
}
