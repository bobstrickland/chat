import { Kafka, logLevel } from "kafkajs";

import { kafkaAuthOptions } from "./kafkaAuth.js";
import { registerCompressionCodecs } from "./kafkaCompression.js";

/**
 * Subscribes to one topic and hands each parsed event to a handler. Used twice
 * (message.sent and search.index), each with its own group so both get every
 * event independently.
 *
 * `fromBeginning: true` (earliest): unlike the messaging DELIVERY consumer
 * (which wants only live traffic and uses latest), the search indexer wants the
 * index to be COMPLETE — on a fresh deploy it should backfill whatever is still
 * in the log rather than start blank. Indexing is an idempotent upsert (doc id
 * is the message/user id), so reprocessing the log is harmless.
 */
export function createIndexConsumer({ brokers, topic, groupId, handler }) {
  registerCompressionCodecs(); // Snappy — kafkajs only ships GZIP
  const kafka = new Kafka({
    clientId: "search-service",
    brokers: brokers.split(",").map((b) => b.trim()),
    // ERROR, not NOTHING: kafkajs reports a crashed consumer / broker
    // failure at ERROR, and suppressing it meant a wedged consumer looked
    // like a perfectly healthy service that had silently stopped consuming.
    logLevel: logLevel.ERROR,
    // TLS + SASL/OAUTHBEARER when KAFKA_AUTH=iam; nothing when plaintext (local).
    ...kafkaAuthOptions(),
  });
  const consumer = kafka.consumer({ groupId });

  return {
    async start() {
      await consumer.connect();
      await consumer.subscribe({ topic, fromBeginning: true });
      // eslint-disable-next-line no-console
      console.log(`[search] indexing ${topic} (group ${groupId})`);
      await consumer.run({
        eachMessage: async ({ message }) => {
          try {
            await handler(JSON.parse(message.value.toString()));
          } catch (err) {
            // A poison record must not stall the partition — log and move on.
            // eslint-disable-next-line no-console
            console.error(`[search] indexing ${topic} failed: ${err.message}`);
          }
        },
      });
    },
    async stop() {
      await consumer.disconnect();
    },
  };
}
