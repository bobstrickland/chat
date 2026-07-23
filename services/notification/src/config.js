import { createDynamoClient } from "./clients/dynamoClient.js";
import { createDeviceTokenRepository } from "./clients/deviceTokenRepository.js";
import { createWebPushSender } from "./clients/webPushSender.js";
import { createTokenVerifier } from "./clients/jwksVerifier.js";

/** Dependency bundle every core/ function receives — the config.js pattern. */
export function getDependencies() {
  const docClient = createDynamoClient({
    region: process.env.AWS_REGION,
    endpoint: process.env.DYNAMODB_ENDPOINT,
  });

  return {
    deviceTokenRepository: createDeviceTokenRepository(docClient, process.env.DEVICE_TOKENS_TABLE),
    webPushSender: createWebPushSender({
      publicKey: process.env.VAPID_PUBLIC_KEY,
      privateKey: process.env.VAPID_PRIVATE_KEY,
      subject: process.env.VAPID_SUBJECT,
    }),
    verifyToken: createTokenVerifier(process.env.COGNITO_JWKS_URL),
  };
}

/** The VAPID public key is safe to hand to the browser (it's public by design). */
export function getVapidPublicKey() {
  const key = process.env.VAPID_PUBLIC_KEY;
  if (!key) {
    throw new Error("VAPID_PUBLIC_KEY is required");
  }
  return key;
}

export function getConsumerConfig() {
  return {
    brokers: process.env.KAFKA_BROKERS,
    topic: process.env.TOPIC_NOTIFICATION_TRIGGER ?? "notification.trigger",
    groupId: process.env.NOTIFICATION_CONSUMER_GROUP ?? "notification",
  };
}
