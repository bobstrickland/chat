/**
 * Provisions a profile for a newly-confirmed account.
 *
 * Called service-to-service by Auth's postConfirmation trigger — never by an
 * end user, which is why it takes a raw userId instead of deriving one from a
 * bearer token. The adapter is responsible for authenticating the caller as a
 * trusted internal service before reaching this.
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

/**
 * A new account has no display name yet, and the chat UI needs *something*
 * renderable immediately. The local-part of the email is the least-surprising
 * default; the user can change it via PATCH.
 *
 * Note this is the only place Profile touches an email, and it is not stored
 * — email remains Auth's data (CLAUDE.md "No shared databases").
 */
function defaultDisplayName(email) {
  if (!email || typeof email !== "string" || !email.includes("@")) {
    return "New User";
  }
  return email.split("@")[0].slice(0, 64) || "New User";
}
