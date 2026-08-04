import { readFileSync } from "node:fs";

import { createDynamoClient } from "./clients/dynamoClient.js";
import { createDeviceTokenRepository } from "./clients/deviceTokenRepository.js";
import { createWebPushSender } from "./clients/webPushSender.js";
import { createFcmSender } from "./clients/fcmSender.js";
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
    fcmSender: createFcmSender(getFcmConfig()),
    verifyToken: createTokenVerifier(process.env.COGNITO_JWKS_URL),
  };
}

/**
 * FCM credentials come from a Firebase service-account JSON, supplied either
 * inline (`FCM_SERVICE_ACCOUNT_JSON`) or as a mounted file path
 * (`FCM_SERVICE_ACCOUNT_FILE`) — the file form is what docker-compose uses.
 *
 * Missing or unparseable credentials are NOT fatal: they disable the android
 * branch and log why. Web push has to keep working on a machine with no Firebase
 * project, which is the normal local-dev state.
 */
function getFcmConfig() {
  if (process.env.FCM_ENABLED !== "true") {
    return { enabled: false };
  }

  const account = readServiceAccount();
  if (!account) {
    return { enabled: false };
  }
  if (!account.project_id || !account.client_email || !account.private_key) {
    // eslint-disable-next-line no-console
    console.warn(
      "[notification] FCM_ENABLED=true but the service account is missing " +
        "project_id/client_email/private_key — android push disabled"
    );
    return { enabled: false };
  }

  return {
    enabled: true,
    projectId: process.env.FCM_PROJECT_ID || account.project_id,
    clientEmail: account.client_email,
    // An inline env var carries literal "\n" sequences; a JSON file has real ones.
    privateKey: account.private_key.replace(/\\n/g, "\n"),
    channelId: process.env.FCM_ANDROID_CHANNEL_ID || "messages",
  };
}

function readServiceAccount() {
  const inline = process.env.FCM_SERVICE_ACCOUNT_JSON;
  const file = process.env.FCM_SERVICE_ACCOUNT_FILE;
  try {
    if (inline) return JSON.parse(inline);
    if (file) return JSON.parse(readFileSync(file, "utf8"));
    // eslint-disable-next-line no-console
    console.warn(
      "[notification] FCM_ENABLED=true but neither FCM_SERVICE_ACCOUNT_JSON nor " +
        "FCM_SERVICE_ACCOUNT_FILE is set — android push disabled"
    );
    return null;
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn(
      `[notification] FCM service account unreadable (${err.message}) — android push disabled`
    );
    return null;
  }
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
