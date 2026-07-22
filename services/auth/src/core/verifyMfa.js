/**
 * @param {{ identityProvider: import('../providers/IdentityProvider.js').IdentityProvider }} deps
 * @param {{ accessToken?: string, code: string, mfaSession?: string, email?: string }} input
 */
export async function verifyMfa({ identityProvider }, input) {
  if (!input.code) {
    throw new Error("code is required");
  }
  if (!input.accessToken && !input.mfaSession) {
    throw new Error("either accessToken (post-enrollment) or mfaSession (login challenge) is required");
  }

  return identityProvider.verifyMfa(input);
}
