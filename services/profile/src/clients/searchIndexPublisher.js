import { Kafka, logLevel } from "kafkajs";

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
    return { async publishProfile() {} };
  }

  const kafka = new Kafka({
    clientId: "profile-service",
    brokers: brokers.split(",").map((b) => b.trim()),
    logLevel: logLevel.NOTHING,
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
              value: JSON.stringify({
                kind: "profile",
                userId: profile.userId,
                displayName: profile.displayName ?? "",
                bio: profile.bio ?? "",
              }),
            },
          ],
        });
      } catch (err) {
        // eslint-disable-next-line no-console
        console.error(`[profile] search.index publish failed: ${err.message}`);
      }
    },
  };
}
