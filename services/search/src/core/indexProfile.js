/**
 * Index a profile for "find a person" search, from a search.index event
 * published by the Profile service on create/update.
 *
 * The event is a generic indexing envelope: `{ kind, userId, displayName, bio }`.
 * Only `kind: "profile"` is understood today; anything else is ignored so the
 * shared search.index topic can carry other doc kinds later without breaking us.
 *
 * @param {{ openSearch: object }} deps
 * @param {{ kind, userId, displayName, bio }} event
 * @returns {Promise<{ indexed: boolean }>}
 */
export async function indexProfile({ openSearch }, event) {
  if (event?.kind !== "profile") return { indexed: false };
  if (!event.userId) {
    throw new Error("profile index event missing userId");
  }
  await openSearch.indexProfile({
    userId: event.userId,
    displayName: event.displayName ?? "",
    bio: event.bio ?? "",
  });
  return { indexed: true };
}
