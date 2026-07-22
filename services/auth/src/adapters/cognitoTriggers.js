/**
 * Cognito Lambda triggers. Per design doc Section 9 / CLAUDE.md: these are
 * thin wrappers only — no business logic lives here. If you're tempted to
 * add logic in this file, it belongs in core/ instead, called from here.
 */

/**
 * Post-confirmation trigger — fires after a user verifies their email.
 * Provisions a Profile record. (Profile Service call/event wiring is a
 * later build step — this is the trigger point, left as a TODO hook.)
 */
export const postConfirmation = async (event) => {
  const { sub, email } = event.request.userAttributes;

  // TODO: call Profile Service (or publish a `user.registered` event to
  // MSK) once that service/topic exists. Intentionally not implemented yet
  // — see build sequence Phase 2.
  // eslint-disable-next-line no-console
  console.log(`post-confirmation: user registered userId=${sub} email=${email}`);

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
