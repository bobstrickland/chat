/**
 * @param {{ identityProvider: import('../providers/IdentityProvider.js').IdentityProvider }} deps
 * @param {{ refreshToken: string }} input
 */
export async function refreshToken({ identityProvider }, input) {
  if (!input.refreshToken) {
    throw new Error("refreshToken is required");
  }

  return identityProvider.refreshToken({ refreshToken: input.refreshToken });
}
