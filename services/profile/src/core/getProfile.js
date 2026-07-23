/**
 * Any authenticated user may read any profile — a chat app has to render
 * display names and avatars for everyone in a conversation. Profiles
 * therefore contain no private data; anything sensitive stays in Auth.
 *
 * @param {{ profileRepository: object }} deps
 * @param {{ userId: string, callerUserId: string }} input
 */
export async function getProfile({ profileRepository }, input) {
  if (!input.callerUserId) {
    throw new Error("unauthenticated");
  }
  if (!input.userId) {
    throw new Error("userId is required");
  }

  const profile = await profileRepository.get({ userId: input.userId });
  if (!profile) {
    const err = new Error("profile not found");
    err.code = "NOT_FOUND";
    throw err;
  }

  return profile;
}
