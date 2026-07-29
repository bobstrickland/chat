/**
 * Delete a user's profile and everything Profile owns for them (Phase 12.5).
 * Users may only delete their OWN profile.
 *
 * This is the within-Profile-domain cascade:
 *   1. the profile row,
 *   2. the user's OWN contacts (rows keyed by their userId),
 *   3. de-index them from people-search.
 *
 * Deliberately NOT here:
 *   - Cognito account + Auth `Users` row — those are Auth's, and cascading into
 *     them from Profile would be a cross-service write (CLAUDE.md). Full account
 *     deletion is orchestrated by Auth (`DELETE /auth/account`), which calls this.
 *   - OTHER users' contact lists that contain the deleted user. `contacts` has no
 *     reverse index, and scanning a user table is a trap (CLAUDE.md). We leave
 *     those rows: `listContacts` already null-safes a missing profile (renders as
 *     "Unknown"), so they degrade gracefully rather than breaking.
 *   - The avatar's S3 object — orphaned, same known limitation as Phase 12's media.
 *
 * Steps 2–3 are best-effort: the profile row is the thing that must go; a failure
 * cleaning up contacts or the search index is logged, not fatal, so a partial
 * infra outage can't leave the user unable to delete their profile.
 *
 * @param {{ profileRepository: object, contactRepository: object, searchIndexPublisher?: object }} deps
 * @param {{ userId: string, callerUserId: string }} input
 */
export async function deleteProfile(
  { profileRepository, contactRepository, searchIndexPublisher },
  input
) {
  if (!input.callerUserId) {
    throw new Error("unauthenticated");
  }
  if (input.userId !== input.callerUserId) {
    const err = new Error("cannot delete another user's profile");
    err.code = "FORBIDDEN";
    throw err;
  }

  await profileRepository.remove({ userId: input.userId });

  // The user's own contact list.
  try {
    const own = await contactRepository.list({ userId: input.userId });
    for (const c of own) {
      await contactRepository.remove({ userId: input.userId, contactId: c.contactId });
    }
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(`[profile] contact cleanup failed for ${input.userId}: ${err.message}`);
  }

  // Remove from people-search.
  try {
    await searchIndexPublisher?.publishProfileDeleted?.(input.userId);
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(`[profile] search de-index failed for ${input.userId}: ${err.message}`);
  }

  return { deleted: true, userId: input.userId };
}
