/**
 * @param {{ identityProvider: import('../providers/IdentityProvider.js').IdentityProvider }} deps
 * @param {{ accessToken: string }} input
 */
export async function enrollMfa({ identityProvider }, input) {
  if (!input.accessToken) {
    throw new Error("accessToken is required");
  }

  return identityProvider.enrollMfa({ accessToken: input.accessToken });
}
