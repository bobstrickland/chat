/**
 * @param {{ identityProvider: import('../providers/IdentityProvider.js').IdentityProvider }} deps
 * @param {{ token: string }} input
 */
export async function verifyToken({ identityProvider }, input) {
  if (!input.token) {
    throw new Error("token is required");
  }

  return identityProvider.verifyToken({ token: input.token });
}
