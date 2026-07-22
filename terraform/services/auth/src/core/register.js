/**
 * @param {import('../providers/IdentityProvider.js').IdentityProvider} identityProvider
 * @param {{ email: string, password: string }} input
 */
export async function register(identityProvider, input) {
  if (!input.email || !input.password) {
    throw new Error("email and password are required");
  }
  if (input.password.length < 12) {
    throw new Error("password must be at least 12 characters");
  }

  return identityProvider.register({ email: input.email, password: input.password });
}
