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
        phone: { type: "keyword" }, // raw, as entered
        phoneDigits: { type: "keyword" }, // normalized (digits only) for substring match
        tags: { type: "text", fields: { raw: { type: "keyword" } } },
        visibility: { type: "keyword" },
      },
    },
  };

  async function ensureIndex(name) {
    const exists = await client.indices.exists({ index: name });
    if (!exists.body) {
      await client.indices.create({ index: name, body: { mappings: MAPPINGS[name] } });
      // eslint-disable-next-line no-console
      console.log(`[search] created index ${name}`);
      return;
    }
    // Index already exists (e.g. created by an earlier phase). Adding NEW fields
    // to a mapping is allowed and idempotent — re-sending existing fields is a
    // no-op, so this safely evolves the schema (Phase 10 profile fields) in place.
    await client.indices.putMapping({ index: name, body: MAPPINGS[name] });
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

    /** Remove a profile from people-search (used when it stops being PUBLIC). */
    async deleteProfile(userId) {
      try {
        await client.delete({ index: profilesIndex, id: userId, refresh: true });
      } catch (err) {
        if (err.meta?.statusCode !== 404) throw err; // already absent is fine
      }
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

    /**
     * People search over the Phase 10 searchable fields: display name, tags, and
     * phone. Phone matches on a digits-only normalization so "(555) 123" and
     * "5551234" line up. `filter visibility=PUBLIC` is belt-and-suspenders — the
     * indexer already keeps only PUBLIC profiles here — so non-public can never leak.
     * Matching must match all terms up to a maximum of 6 terms
     */
    async searchProfiles(q, size) {
      // 1. Phone recognition and term consolidation logic
      const phonePattern = /(?:\+\d{1,4}[\s\-]*)?\(?\d+\)?(?:[\s\-]+\(?\d+\)?)+/g;
      const phonePatternMatch = q.match(phonePattern);
      const digits = phonePatternMatch ? phonePatternMatch[0].replace(/\D/g, "") : "";

      const standardizedStr = q.replace(phonePattern, '__PHONE_NUMBER__');
      const matches = standardizedStr.trim().match(/\S+/g);
      const parameterCount = matches ? (matches.length > 5 ? 6 : matches.length) : 0;

      // 2. Build the structural OpenSearch clauses
      const should = [{ multi_match: { query: q, fields: ["displayName^3", "tags^2"],minimum_should_match: parameterCount} }];
      if (digits.length >= 7) {
        should.push({ wildcard: { phoneDigits: `*${digits}*` } });
      }
      const res = await client.search({
        index: profilesIndex,
        body: {
          size,
          query: {
            bool: {
              should,
              minimum_should_match: 1,
              filter: { term: { visibility: "PUBLIC" } },
            },
          },
        },
      });

      // 4. CROSS-VERSION SAFE DATA EXTRACTION:
      // Checks v3.x object format first, then falls back to v2.x body wrapping
      const hits = res.hits?.hits || res.body?.hits?.hits || [];
      
      return hits.map((h) => h._source);
    },
  };
}
