/**
 * Index (or remove) a profile for people-search, from a search.index event
 * published by the Profile service on create/update.
 *
 * Envelope: `{ kind:"profile", userId, displayName, phone, tags, visibility }`.
 * Only `kind:"profile"` is understood; anything else is ignored so the shared
 * search.index topic can carry other doc kinds later.
 *
 * Visibility is enforced HERE, by construction: only **PUBLIC** profiles are kept
 * in the searchable index. A non-public profile is DELETED (covering a
 * PUBLIC→PRIVATE flip), so a non-public profile can never appear in results —
 * stronger than filtering at query time (the query still filters too, as a backstop).
 *
 * @param {{ openSearch: object }} deps
 * @returns {Promise<{ indexed: boolean, removed?: boolean }>}
 */
export async function indexProfile({ openSearch }, event) {
  if (event?.kind !== "profile") return { indexed: false };
  if (!event.userId) {
    throw new Error("profile index event missing userId");
  }

  if (event.visibility !== "PUBLIC") {
    await openSearch.deleteProfile(event.userId);
    return { indexed: false, removed: true };
  }

  const phone = event.phone ?? "";
  await openSearch.indexProfile({
    userId: event.userId,
    displayName: event.displayName ?? "",
    phone,
    phoneDigits: phone.replace(/\D/g, ""),
    tags: Array.isArray(event.tags) ? event.tags : [],
    visibility: "PUBLIC",
  });
  return { indexed: true };
}
