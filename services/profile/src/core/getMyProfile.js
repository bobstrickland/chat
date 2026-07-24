import { defaultDisplayName } from "./displayName.js";

/**
 * Fetch the caller's OWN profile, lazily creating a default one if it doesn't
 * exist yet.
 *
 * This is what makes profiles appear for users the `postConfirmation` trigger
 * never provisioned — most importantly **federated (Google) users**, for whom
 * postConfirmation never fires at all (Cognito auto-creates external-IdP users
 * and skips the self-signup confirmation flow). It also covers the current
 * local state where the trigger isn't attached to the pool.
 *
 * Distinct from getProfile (viewing OTHERS), which still 404s on a missing
 * profile — you don't conjure a profile for a stranger, only for yourself.
 *
 * createIfAbsent is conditional + idempotent, so two concurrent first-access
 * calls converge on one profile rather than racing.
 *
 * @param {{ profileRepository: object }} deps
 * @param {{ userId: string, email?: string|null }} input
 */
export async function getMyProfile({ profileRepository, searchIndexPublisher }, input) {
  if (!input.userId) {
    throw new Error("userId is required");
  }
  const { profile, created } = await profileRepository.createIfAbsent({
    userId: input.userId,
    displayName: defaultDisplayName(input.email),
  });
  // Only index on first creation — getMyProfile runs on every app load, and an
  // unchanged profile doesn't need re-indexing on each visit.
  if (created) await searchIndexPublisher?.publishProfile(profile);
  return profile;
}
