/**
 * @param {{ identityProvider: import('../providers/IdentityProvider.js').IdentityProvider,
 *           userRepository: ReturnType<typeof import('../clients/userRepository.js').createUserRepository> }} deps
 * @param {{ email: string, password: string }} input
 */
export async function register({ identityProvider, userRepository }, input) {
  if (!input.email || !input.password) {
    throw new Error("email and password are required");
  }
  if (input.password.length < 12) {
    throw new Error("password must be at least 12 characters");
  }

  const result = await identityProvider.register({
    email: input.email,
    password: input.password,
  });

  // Cognito is the source of truth for identity; this table is Auth's own
  // projection of it. The write is deliberately best-effort: the account
  // already exists in Cognito by this point, and failing the request here
  // would strand the caller — a retry would just hit UsernameExistsException
  // and they could never converge.
  //
  // Reconciliation: the postConfirmation trigger upserts the same row via
  // markConfirmed(), which creates it if this write was the one that got
  // lost. That makes a dropped write self-healing at confirmation time
  // rather than permanent.
  try {
    await userRepository.putUser({
      email: result.email,
      userId: result.userId,
      status: "UNCONFIRMED",
    });
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(
      `[register] Cognito signup succeeded but Users write failed for ${result.email} ` +
        `(userId=${result.userId}): ${err.message} — will reconcile on postConfirmation`
    );
  }

  return result;
}
