import { basicIdentity } from "./basicIdentity.js";

/**
 * A user's contacts (Phase 11). A contact is just another userId the caller has
 * added. Contacts are self-only: you manage your OWN list, keyed by your token.
 *
 * Adding is one-directional and requires the target to exist. The returned shape
 * is the added user's basic identity, so the client can render the new row
 * immediately without a follow-up fetch.
 *
 * @param {{ contactRepository, profileRepository }} deps
 * @param {{ callerUserId: string, contactId: string }} input
 */
export async function addContact({ contactRepository, profileRepository }, input) {
  if (!input.callerUserId) throw new Error("unauthenticated");
  if (!input.contactId) throw new Error("contactId is required");
  if (input.contactId === input.callerUserId) {
    throw new Error("cannot add yourself as a contact");
  }

  const target = await profileRepository.get({ userId: input.contactId });
  if (!target) {
    const err = new Error("no such user");
    err.code = "NOT_FOUND";
    throw err;
  }

  await contactRepository.add({ userId: input.callerUserId, contactId: input.contactId });
  return identity(target, input.contactId);
}

export async function removeContact({ contactRepository }, input) {
  if (!input.callerUserId) throw new Error("unauthenticated");
  if (!input.contactId) throw new Error("contactId is required");
  await contactRepository.remove({ userId: input.callerUserId, contactId: input.contactId });
  return { removed: true, contactId: input.contactId };
}

/**
 * The caller's contacts, each enriched with basic identity (name + avatar) for
 * rendering. N+1 gets, but a personal contact list is small and bounded.
 */
export async function listContacts({ contactRepository, profileRepository }, input) {
  if (!input.callerUserId) throw new Error("unauthenticated");

  const rows = await contactRepository.list({ userId: input.callerUserId });
  const contacts = [];
  for (const row of rows) {
    const profile = await profileRepository.get({ userId: row.contactId });
    contacts.push({
      ...(profile ? identity(profile, row.contactId) : { userId: row.contactId, displayName: null, avatarMediaId: null }),
      addedAt: row.createdAt,
    });
  }
  return { contacts };
}

/** Basic identity minus the view-restriction flags — a contact row, not a profile view. */
function identity(profile, userId) {
  const { restricted, visibility, ...id } = basicIdentity({ ...profile, userId });
  return id;
}
