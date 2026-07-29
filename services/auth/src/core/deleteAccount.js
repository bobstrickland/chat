/**
 * Full account deletion (Phase 12.5). Orchestrated by Auth because Auth owns
 * identity (Cognito + the `Users` ledger). The caller can only delete their OWN
 * account — it's driven entirely by their access token.
 *
 * Order matters, and is chosen so nothing irreversible happens until the
 * reversible cleanup is done, and so every token-dependent call runs while the
 * token is still valid:
 *
 *   1. Profile data — call Profile's self-authenticated DELETE with the caller's
 *      bearer (profile row + their contacts + search de-index). BEST-EFFORT:
 *      logged, not fatal. A Profile outage must not trap a user in an account
 *      they've asked to delete; leftover profile data for a deleted identity is
 *      harmless (nothing re-reads it) and a later `user.deleted` sweep can reap it.
 *   2. Identity provider — GetUser (capture email) then DeleteUser. This is the
 *      irreversible finalizer and invalidates the token, so it goes last among
 *      the token-dependent steps.
 *   3. Auth's `Users` ledger row (keyed by the email from step 2). BEST-EFFORT —
 *      it's a projection, not the source of truth, and the delete is idempotent.
 *
 * Not here (cross-service / out of scope, matching the design): OTHER users'
 * contacts that reference this user (left to degrade gracefully — Profile has no
 * reverse index), and the avatar's S3 object (orphaned, same as Phase 12 media).
 *
 * @param {{ identityProvider: object, userRepository: object, profileService: object }} deps
 * @param {{ userId: string, accessToken: string }} input
 */
export async function deleteAccount(
  { identityProvider, userRepository, profileService },
  input
) {
  if (!input.accessToken) {
    throw new Error("unauthenticated");
  }
  if (!input.userId) {
    throw new Error("userId is required");
  }

  // 1. Profile-owned data (best-effort — must run before the token is killed).
  try {
    await profileService.deleteProfileAsUser({
      userId: input.userId,
      accessToken: input.accessToken,
    });
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(
      `[deleteAccount] profile cleanup failed for ${input.userId}: ${err.message} ` +
        `— proceeding with account deletion; profile data may be orphaned`
    );
  }

  // 2. The identity itself — irreversible finalizer; returns the email.
  const { email } = await identityProvider.deleteAccount({ accessToken: input.accessToken });

  // 3. Auth's ledger row (best-effort; nothing downstream reads it).
  if (email) {
    try {
      await userRepository.remove({ email });
    } catch (err) {
      // eslint-disable-next-line no-console
      console.error(`[deleteAccount] Users ledger cleanup failed for ${email}: ${err.message}`);
    }
  }

  return { deleted: true, userId: input.userId };
}
