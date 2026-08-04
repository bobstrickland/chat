import express from "express";
import { getDependencies, getVapidPublicKey, getConsumerConfig } from "../config.js";
import { loadSecrets } from "../secrets.js";
import { registerDevice } from "../core/registerDevice.js";
import { unregisterDevice } from "../core/unregisterDevice.js";
import { sendPushToRecipient } from "../core/sendPushToRecipient.js";
import { createNotificationConsumer } from "../clients/kafkaConsumer.js";

const app = express();
app.use(express.json());

// Secrets BEFORE dependencies: getDependencies() reads VAPID/FCM values straight
// out of the environment, so hydration has to finish first. No-op unless
// SECRETS_PROVIDER=awssm. Top-level await — this module is ESM.
const secrets = await loadSecrets();
if (secrets.provider === "awssm") {
  // eslint-disable-next-line no-console
  console.log(`[secrets] loaded from Secrets Manager: ${secrets.loaded.join(", ") || "none"}`);
}

const deps = getDependencies();

async function authenticate(req, res, next) {
  const header = req.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : null;
  if (!token) return res.status(401).json({ error: "missing bearer token" });
  try {
    req.claims = await deps.verifyToken(token);
    next();
  } catch {
    res.status(401).json({ error: "invalid token" });
  }
}

// Client fetches the VAPID public key to subscribe with.
app.get("/push/config", (_req, res) => {
  res.json({ publicKey: getVapidPublicKey() });
});

// Register (or re-register) this browser/device for push. Called on login.
app.post("/device-tokens", authenticate, async (req, res) => {
  try {
    const result = await registerDevice(deps, {
      userId: req.claims.userId,
      deviceId: req.body?.deviceId,
      platform: req.body?.platform,
      subscription: req.body?.subscription,
    });
    res.status(201).json(result);
  } catch (err) {
    const status = err.message === "unauthenticated" ? 401 : 400;
    res.status(status).json({ error: err.message });
  }
});

// Drop this device's registration — called on sign-out so the next user of the
// phone doesn't receive the previous user's pushes.
app.delete("/device-tokens/:deviceId", authenticate, async (req, res) => {
  try {
    const result = await unregisterDevice(deps, {
      userId: req.claims.userId,
      deviceId: req.params.deviceId,
    });
    res.status(200).json(result);
  } catch (err) {
    const status = err.message === "unauthenticated" ? 401 : 400;
    res.status(status).json({ error: err.message });
  }
});

app.get("/health", (_req, res) => res.status(200).json({ status: "ok" }));

const port = process.env.PORT ?? 3000;
app.listen(port, () => {
  // eslint-disable-next-line no-console
  console.log(`notification-service (httpServer adapter) listening on :${port}`);
});

// Start the notification.trigger consumer alongside the API (in AWS this is a
// separate MSK-triggered Lambda over the same sendPushToRecipient core).
const consumer = createNotificationConsumer({
  ...getConsumerConfig(),
  handler: async (event) => {
    const result = await sendPushToRecipient(deps, event);
    // eslint-disable-next-line no-console
    console.log(
      `[notification] trigger recipient=${event.recipientId} devices=${result.devices} ` +
        `sent=${result.sent} pruned=${result.pruned} skipped=${result.skipped} failed=${result.failed}` +
        (result.errors.length ? ` errors=${JSON.stringify(result.errors)}` : "")
    );
  },
});
consumer.start().catch((err) => {
  // eslint-disable-next-line no-console
  console.error(`[notification] consumer failed to start: ${err.message}`);
});
