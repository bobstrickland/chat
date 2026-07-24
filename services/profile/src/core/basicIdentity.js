/**
 * The always-visible slice of a profile: enough to render someone in a chat or
 * a search result (name + avatar), but none of the detail fields (bio, phone,
 * links, tags).
 *
 * Phase 11 note — reconciling visibility with the chat UI: a PRIVATE/CONTACTS
 * profile you're not authorized to view still needs its NAME and AVATAR to
 * render wherever you already share context (a conversation, a search hit). So
 * "not viewable" hides the DETAILS, not this basic identity. The profile-view
 * screen shows a "restricted" state when it only gets this back.
 */
export function basicIdentity(profile) {
  return {
    userId: profile.userId,
    displayName: profile.displayName,
    avatarMediaId: profile.avatarMediaId ?? null,
    visibility: profile.visibility ?? "PUBLIC",
    restricted: true,
  };
}
