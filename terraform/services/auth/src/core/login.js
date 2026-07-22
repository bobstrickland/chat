/**
 * @param {import('../providers/IdentityProvider.js').IdentityProvider} identityProvider
 * @param {{ email: string, password: string }} input
 */
export async function login(identityProvider, input) {
  if (!input.email || !input.password) {
    throw new Error("email and password are required");
  }

  return identityProvider.authenticate({ email: input.email, password: input.password });
}
