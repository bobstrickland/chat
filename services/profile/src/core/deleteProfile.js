/**
 * Users may only delete their own profile.
 *
 * Deliberately a hard delete of the profile row only. This does NOT delete
 * the Cognito account or the Auth `Users` row — account deletion is Auth's
 * concern, and cascading across services from here would be a direct
 * cross-service write (CLAUDE.md). Full account deletion becomes a
 * `user.deleted` event once MSK is in play.
 *
 * @param {{ profileRepository: object }} deps
 * @param {{ userId: string, callerUserId: string }} input
 */
export async function deleteProfile({ profileRepository }, input) {
  if (!input.callerUserId) {
    throw new Error("unauthenticated");
  }
  if (input.userId !== input.callerUserId) {
    const err = new Error("cannot delete another user's profile");
    err.code = "FORBIDDEN";
    throw err;
  }

  await profileRepository.remove({ userId: input.userId });
  return { deleted: true, userId: input.userId };
}
