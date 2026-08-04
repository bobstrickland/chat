import { getDependencies, getVapidPublicKey } from "../config.js";
import { loadSecrets } from "../secrets.js";
import { registerDevice } from "../core/registerDevice.js";
import { unregisterDevice } from "../core/unregisterDevice.js";
import { sendPushToRecipient } from "../core/sendPushToRecipient.js";

// Per CLAUDE.md: no reliance on warm context for correctness — lazy init is a
// warm-start bonus only.
let deps;

/**
 * Secrets are fetched on the first invocation of a container and reused while it
 * lives. Correctness never depends on that reuse: a cold start simply fetches
 * again. The promise (not a boolean) is memoized so two concurrent invocations
 * can't both fetch.
 */
let secretsReady;

async function ready() {
  secretsReady ??= loadSecrets();
  await secretsReady;
  deps ??= getDependencies();
}

/**
 * AWS-side adapters. Two entry points in one file, calling the same core:
 *   - `handler`      : API Gateway HTTP (device registration + /push/config)
 *   - `mskHandler`   : MSK-triggered consumer of notification.trigger (push send)
 * Not exercised locally (httpServer.js is); present per the both-adapters rule.
 */

export const handler = async (event) => {
  await ready();
  const method = event.requestContext?.http?.method;
  const path = event.requestContext?.http?.path ?? "";

  if (path === "/health") return reply(200, { status: "ok" });
  if (method === "GET" && path === "/push/config") {
    return reply(200, { publicKey: getVapidPublicKey() });
  }
  if (method === "POST" && path === "/device-tokens") {
    let claims;
    try {
      claims = await deps.verifyToken(bearer(event));
    } catch {
      return reply(401, { error: "invalid token" });
    }
    try {
      const body = event.body ? JSON.parse(event.body) : {};
      const result = await registerDevice(deps, {
        userId: claims.userId,
        deviceId: body.deviceId,
        platform: body.platform,
        subscription: body.subscription,
      });
      return reply(201, result);
    } catch (err) {
      return reply(400, { error: err.message });
    }
  }
  if (method === "DELETE" && path.startsWith("/device-tokens/")) {
    let claims;
    try {
      claims = await deps.verifyToken(bearer(event));
    } catch {
      return reply(401, { error: "invalid token" });
    }
    try {
      const deviceId = decodeURIComponent(path.slice("/device-tokens/".length));
      return reply(200, await unregisterDevice(deps, { userId: claims.userId, deviceId }));
    } catch (err) {
      return reply(400, { error: err.message });
    }
  }
  return reply(404, { error: "not found" });
};

/** MSK event source: each record is a notification.trigger event. */
export const mskHandler = async (event) => {
  await ready();
  for (const records of Object.values(event.records ?? {})) {
    for (const record of records) {
      const value = Buffer.from(record.value, "base64").toString("utf8");
      await sendPushToRecipient(deps, JSON.parse(value));
    }
  }
  return { ok: true };
};

function bearer(event) {
  const h = event.headers?.authorization ?? event.headers?.Authorization ?? "";
  return h.startsWith("Bearer ") ? h.slice(7) : "";
}

function reply(statusCode, body) {
  return { statusCode, body: JSON.stringify(body) };
}
