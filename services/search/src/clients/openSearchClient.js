import { Client } from "@opensearch-project/opensearch";

/**
 * Thin wrapper over the OpenSearch client — the only place the OpenSearch
 * query DSL lives (core/ stays free of it, the clients/ contract).
 *
 * Two indices:
 *   - messages : one doc per chat message (id = messageId). Full-text on `body`,
 *                filtered at query time by the caller's conversationIds so a user
 *                only ever searches conversations they belong to.
 *   - profiles : one doc per user (id = userId). Full-text on `displayName`/`bio`
 *                for "find a person" search.
 *
 * Indexing is an upsert (index by id), so a Kafka redelivery just rewrites the
 * same doc — the consumer can be at-least-once without creating duplicates.
 */
export function createOpenSearchClient({ node, messagesIndex, profilesIndex }) {
  const client = new Client({ node });

  const MAPPINGS = {
    [messagesIndex]: {
      properties: {
        messageId: { type: "keyword" },
        conversationId: { type: "keyword" },
        senderId: { type: "keyword" },
        body: { type: "text" },
        sentAt: { type: "date" },
      },
    },
    [profilesIndex]: {
      properties: {
        userId: { type: "keyword" },
        displayName: { type: "text", fields: { raw: { type: "keyword" } } },
        bio: { type: "text" },
      },
    },
  };

  async function ensureIndex(name) {
    const exists = await client.indices.exists({ index: name });
    if (exists.body) return;
    await client.indices.create({ index: name, body: { mappings: MAPPINGS[name] } });
    // eslint-disable-next-line no-console
    console.log(`[search] created index ${name}`);
  }

  return {
    /** Idempotent — safe to run on every boot. Create-if-missing, never delete. */
    async ensureIndices() {
      await ensureIndex(messagesIndex);
      await ensureIndex(profilesIndex);
    },

    async indexMessage(doc) {
      await client.index({ index: messagesIndex, id: doc.messageId, body: doc, refresh: true });
    },

    async indexProfile(doc) {
      await client.index({ index: profilesIndex, id: doc.userId, body: doc, refresh: true });
    },

    /**
     * Full-text over message bodies, hard-scoped to conversationIds the caller is
     * a member of. An empty conversation list short-circuits to no results — a
     * user with no conversations can match nothing (never "all messages").
     */
    async searchMessages(q, conversationIds, size) {
      if (!conversationIds || conversationIds.length === 0) return [];
      const res = await client.search({
        index: messagesIndex,
        body: {
          size,
          query: {
            bool: {
              must: { match: { body: q } },
              filter: { terms: { conversationId: conversationIds } },
            },
          },
          sort: [{ _score: "desc" }, { sentAt: "desc" }],
        },
      });
      return res.body.hits.hits.map((h) => h._source);
    },

    async searchProfiles(q, size) {
      const res = await client.search({
        index: profilesIndex,
        body: {
          size,
          query: { multi_match: { query: q, fields: ["displayName^2", "bio"] } },
        },
      });
      return res.body.hits.hits.map((h) => h._source);
    },
  };
}
