/**
 * Calls the Messaging service to resolve which conversations a user belongs to.
 *
 * Authorization for message search is enforced by MEMBERSHIP, and membership is
 * owned by Messaging (the Conversations table), not duplicated here (CLAUDE.md:
 * no cross-service table reads). So at search time we ask Messaging — reusing
 * the CALLER'S OWN bearer token — for their conversation list, and scope the
 * OpenSearch query to exactly those ids. No token, no conversations, no results.
 */
export function createMessagingClient({ baseUrl }) {
  return {
    /** @returns {Promise<string[]>} the caller's conversationIds ([] on any failure) */
    async conversationIdsFor(bearerToken) {
      try {
        const res = await fetch(`${baseUrl}/conversations`, {
          headers: { authorization: `Bearer ${bearerToken}` },
        });
        if (!res.ok) return [];
        const data = await res.json();
        return (data.conversations ?? []).map((c) => c.conversationId).filter(Boolean);
      } catch {
        return [];
      }
    },
  };
}
