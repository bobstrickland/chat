import { defaultDisplayName } from "./displayName.js";

/**
 * Provisions a profile for a newly-confirmed account.
 *
 * Called service-to-service by Auth's postConfirmation trigger — never by an
 * end user, which is why it takes a raw userId instead of deriving one from a
 * bearer token. The adapter is responsible for authenticating the caller as a
 * trusted internal service before reaching this.
 *
 * Note: with lazy provisioning now in getMyProfile, this internal path is no
 * longer the ONLY way a profile gets created — but it stays useful for
 * provisioning a profile before the user's first visit (e.g. from a future
 * Lambda trigger or a `user.registered` event).
 *
 * Idempotent: Cognito retries triggers on failure, and a retry must not
 * clobber a profile the user has already edited.
 *
 * @param {{ profileRepository: object }} deps
 * @param {{ userId: string, email?: string, displayName?: string }} input
 */
export async function provisionProfile({ profileRepository }, input) {
  if (!input.userId) {
    throw new Error("userId is required");
  }

  return profileRepository.createIfAbsent({
    userId: input.userId,
    displayName: input.displayName || defaultDisplayName(input.email),
  });
}
