import { basicIdentity } from "./basicIdentity.js";

/**
 * Read another user's profile, honoring their visibility (Phase 11):
 *   - PUBLIC   → full profile to anyone authenticated
 *   - CONTACTS → full profile only if the OWNER has added the caller as a contact
 *                (owner controls who sees their contacts-only profile)
 *   - PRIVATE  → never the full profile to others
 *
 * When not authorized, we return the BASIC IDENTITY (name + avatar) rather than
 * 404 — the caller may share a conversation with this user and still needs to
 * render them. Detail fields (bio/phone/links/tags) are withheld. See
 * basicIdentity.js for why this split exists.
 *
 * (Reading your OWN profile goes through getMyProfile, which never restricts.)
 *
 * @param {{ profileRepository: object, contactRepository: object }} deps
 * @param {{ userId: string, callerUserId: string }} input
 */
export async function getProfile({ profileRepository, contactRepository }, input) {
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

  const visibility = profile.visibility ?? "PUBLIC";
  let authorized = visibility === "PUBLIC";
  if (visibility === "CONTACTS") {
    authorized = await contactRepository.isContact({
      userId: input.userId, // owner
      contactId: input.callerUserId, // did the owner add the caller?
    });
  }

  return authorized ? { ...profile, restricted: false } : basicIdentity(profile);
}
