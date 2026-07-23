/**
 * Cognito Lambda triggers. Per design doc Section 9 / CLAUDE.md: these are
 * thin wrappers only — no business logic lives here. If you're tempted to
 * add logic in this file, it belongs in core/ instead, called from here.
 */
import { getDependencies } from "../config.js";

/**
 * Post-confirmation trigger — fires after a user verifies their email.
 * Provisions a Profile record. (Profile Service call/event wiring is a
 * later build step — this is the trigger point, left as a TODO hook.)
 */
export const postConfirmation = async (event) => {
  const { sub, email } = event.request.userAttributes;
  const { userRepository, profileService } = getDependencies();

  // Auth's own record: flip UNCONFIRMED -> CONFIRMED. Also the reconciliation
  // point for a registration whose Users write was dropped — markConfirmed
  // upserts, so the row exists either way.
  await userRepository.markConfirmed({ email, userId: sub });

  // Profile provisioning (Phase 2). Cross-service API call — Auth never
  // writes the `profiles` table itself. Best-effort: a failure here must not
  // fail the user's confirmation, and provisioning is idempotent on the
  // Profile side, so a Cognito trigger retry converges rather than duplicating.
  try {
    await profileService.provisionProfile({ userId: sub, email });
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(
      `[postConfirmation] profile provisioning failed for userId=${sub}: ${err.message} ` +
        `— account is usable but has no profile; re-provision manually or via replay`
    );
  }

  // eslint-disable-next-line no-console
  console.log(`post-confirmation: user confirmed userId=${sub} email=${email}`);

  // Cognito requires the (possibly mutated) event echoed back. Never throw
  // past this point for non-critical work — a rejected postConfirmation
  // fails the user's confirmation in Cognito.
  return event;
};

/**
 * Pre-token-generation trigger — opportunity to add custom claims before
 * Cognito issues a token. Not used yet; present as a placeholder so the
 * wiring exists when a custom claim requirement shows up.
 */
export const preTokenGeneration = async (event) => {
  return event;
};
