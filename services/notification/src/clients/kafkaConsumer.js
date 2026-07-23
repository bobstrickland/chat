import { Kafka, logLevel } from "kafkajs";

/**
 * Consumes notification.trigger and hands each event to a handler. In the
 * HttpServer/Fargate adapter this runs as a background consumer; in AWS the same
 * handler would be driven by an MSK-triggered Lambda instead.
 */
export function createNotificationConsumer({ brokers, topic, groupId, handler }) {
  const kafka = new Kafka({
    clientId: "notification-service",
    brokers: brokers.split(",").map((b) => b.trim()),
    logLevel: logLevel.NOTHING,
  });
  const consumer = kafka.consumer({ groupId });

  return {
    async start() {
      await consumer.connect();
      await consumer.subscribe({ topic, fromBeginning: false });
      // eslint-disable-next-line no-console
      console.log(`[notification] consuming ${topic}`);
      await consumer.run({
        eachMessage: async ({ message }) => {
          try {
            const event = JSON.parse(message.value.toString());
            await handler(event);
          } catch (err) {
            // A poison message must not stall the partition — log and move on.
            // eslint-disable-next-line no-console
            console.error(`[notification] handling failed: ${err.message}`);
          }
        },
      });
    },
    async stop() {
      await consumer.disconnect();
    },
  };
}
