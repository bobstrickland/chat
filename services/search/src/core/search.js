const MESSAGE_LIMIT = 25;
const USER_LIMIT = 10;

/**
 * Run a search on behalf of the caller.
 *
 *   - messages : full-text over bodies, scoped to the caller's own conversations
 *                (membership resolved via Messaging using the caller's token).
 *   - users    : full-text over profile display names / bios (public directory).
 *
 * `type` selects which slices to run ("messages" | "users" | "all", default all).
 * A blank query returns empty results rather than erroring — a search box fires
 * on keystrokes and shouldn't 400 on an empty field.
 *
 * @param {{ openSearch, messagingClient }} deps
 * @param {{ q: string, type?: string, bearerToken: string }} input
 * @returns {Promise<{ messages: object[], users: object[] }>}
 */
export async function search({ openSearch, messagingClient }, input) {
  const q = (input.q ?? "").trim();
  const type = input.type ?? "all";
  if (!q) return { messages: [], users: [] };

  const wantMessages = type === "all" || type === "messages";
  const wantUsers = type === "all" || type === "users";

  const [messages, users] = await Promise.all([
    wantMessages
      ? messagingClient
          .conversationIdsFor(input.bearerToken)
          .then((ids) => openSearch.searchMessages(q, ids, MESSAGE_LIMIT))
      : Promise.resolve([]),
    wantUsers ? openSearch.searchProfiles(q, USER_LIMIT) : Promise.resolve([]),
  ]);

  return { messages, users };
}
