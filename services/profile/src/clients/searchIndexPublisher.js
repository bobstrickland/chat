import { Kafka, logLevel } from "kafkajs";

import { kafkaAuthOptions } from "./kafkaAuth.js";
import { registerCompressionCodecs } from "./kafkaCompression.js";

/**
 * Publishes a profile onto the `search.index` topic so the Search service can
 * index it for people-search. A generic indexing envelope
 * (`{ kind:"profile", ... }`) rather than a profile-specific topic, matching the
 * shared `search.index` channel in the design.
 *
 * BEST-EFFORT: search indexing is a downstream convenience, never a reason to
 * fail a profile write. Every publish self-catches; if Kafka is down the profile
 * still saves and simply isn't (re)indexed until the next change. When no broker
 * is configured (fully-offline `AUTH_PROVIDER=local` style runs) this is a no-op.
 */
export function createSearchIndexPublisher({ brokers, topic }) {
  if (!brokers) {
    return { async publishProfile() {}, async publishProfileDeleted() {} };
  }

  registerCompressionCodecs(); // Snappy — kafkajs only ships GZIP

  const kafka = new Kafka({
    clientId: "profile-service",
    brokers: brokers.split(",").map((b) => b.trim()),
    // ERROR, not NOTHING: kafkajs reports a crashed consumer / broker
    // failure at ERROR, and suppressing it meant a wedged consumer looked
    // like a perfectly healthy service that had silently stopped consuming.
    logLevel: logLevel.ERROR,
    // TLS + SASL/OAUTHBEARER when KAFKA_AUTH=iam; nothing when plaintext (local).
    ...kafkaAuthOptions(),
  });
  const producer = kafka.producer();
  let connected = null; // connect lazily, once

  return {
    async publishProfile(profile) {
      if (!profile?.userId) return;
      try {
        connected ??= producer.connect();
        await connected;
        await producer.send({
          topic,
          messages: [
            {
              key: profile.userId,
              // Searchable fields (Phase 10: displayName, phone, tags) + visibility,
              // which the Search indexer uses to decide index vs. remove (only
              // PUBLIC profiles are ever searchable).
              value: JSON.stringify({
                kind: "profile",
                userId: profile.userId,
                displayName: profile.displayName ?? "",
                phone: profile.phone ?? "",
                tags: Array.isArray(profile.tags) ? profile.tags : [],
                visibility: profile.visibility ?? "PUBLIC",
              }),
            },
          ],
        });
      } catch (err) {
        // eslint-disable-next-line no-console
        console.error(`[profile] search.index publish failed: ${err.message}`);
      }
    },

    /**
     * De-index a deleted profile from people-search. Sent on the same
     * `search.index` channel: the indexer removes any profile whose visibility
     * isn't PUBLIC (its PUBLIC→PRIVATE removal path), so a non-PUBLIC visibility
     * here deletes the doc with NO search-service change. `deleted:true` is
     * carried for forward-clarity if the indexer ever wants an explicit signal.
     */
    async publishProfileDeleted(userId) {
      if (!userId) return;
      try {
        connected ??= producer.connect();
        await connected;
        await producer.send({
          topic,
          messages: [
            {
              key: userId,
              value: JSON.stringify({
                kind: "profile",
                userId,
                deleted: true,
                visibility: "DELETED", // any non-PUBLIC → indexer removes the doc
              }),
            },
          ],
        });
      } catch (err) {
        // eslint-disable-next-line no-console
        console.error(`[profile] search.index delete publish failed: ${err.message}`);
      }
    },
  };
}
