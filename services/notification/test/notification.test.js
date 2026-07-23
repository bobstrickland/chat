/**
 * Core tested with in-memory fakes — no DynamoDB, no web-push, no Kafka.
 * Possible only because core/ takes its dependencies as arguments.
 */
import test from "node:test";
import assert from "node:assert/strict";

import { registerDevice } from "../src/core/registerDevice.js";
import { sendPushToRecipient } from "../src/core/sendPushToRecipient.js";

function fakeRepo(seed = []) {
  const rows = [...seed];
  return {
    rows,
    async upsert(item) {
      const i = rows.findIndex((r) => r.userId === item.userId && r.deviceId === item.deviceId);
      const row = { ...item, updatedAt: "now" };
      if (i >= 0) rows[i] = row;
      else rows.push(row);
      return { userId: item.userId, deviceId: item.deviceId, platform: item.platform };
    },
    async listForUser(userId) {
      return rows.filter((r) => r.userId === userId);
    },
    async remove({ userId, deviceId }) {
      const i = rows.findIndex((r) => r.userId === userId && r.deviceId === deviceId);
      if (i >= 0) rows.splice(i, 1);
    },
  };
}

function fakeSender(behavior = () => ({ ok: true })) {
  const calls = [];
  return {
    calls,
    async send(subscription, payload) {
      calls.push({ subscription, payload });
      return behavior(subscription);
    },
  };
}

const webSub = { endpoint: "https://push.example/abc", keys: { p256dh: "x", auth: "y" } };

// --- registerDevice -------------------------------------------------------

test("registerDevice stores a web subscription", async () => {
  const deviceTokenRepository = fakeRepo();
  const res = await registerDevice(
    { deviceTokenRepository },
    { userId: "u1", deviceId: "d1", platform: "web", subscription: webSub }
  );
  assert.equal(res.deviceId, "d1");
  assert.equal(deviceTokenRepository.rows.length, 1);
});

test("registerDevice re-registering the same device replaces the subscription", async () => {
  const deviceTokenRepository = fakeRepo();
  const base = { userId: "u1", deviceId: "d1", platform: "web" };
  await registerDevice({ deviceTokenRepository }, { ...base, subscription: webSub });
  await registerDevice(
    { deviceTokenRepository },
    { ...base, subscription: { ...webSub, endpoint: "https://push.example/new" } }
  );
  assert.equal(deviceTokenRepository.rows.length, 1, "upsert, not duplicate");
  assert.equal(deviceTokenRepository.rows[0].subscription.endpoint, "https://push.example/new");
});

test("registerDevice rejects unauthenticated, bad platform, and missing subscription", async () => {
  const deviceTokenRepository = fakeRepo();
  const call = (input) => registerDevice({ deviceTokenRepository }, input);
  await assert.rejects(() => call({ deviceId: "d1", platform: "web", subscription: webSub }), /unauthenticated/);
  await assert.rejects(() => call({ userId: "u1", deviceId: "d1", platform: "fax", subscription: webSub }), /platform/);
  await assert.rejects(() => call({ userId: "u1", deviceId: "d1", platform: "web" }), /subscription is required/);
  await assert.rejects(() => call({ userId: "u1", deviceId: "d1", platform: "web", subscription: {} }), /must include an endpoint/);
});

// --- sendPushToRecipient --------------------------------------------------

test("sends a web push to each of the recipient's web devices", async () => {
  const deviceTokenRepository = fakeRepo([
    { userId: "u1", deviceId: "d1", platform: "web", subscription: webSub },
    { userId: "u1", deviceId: "d2", platform: "web", subscription: { ...webSub, endpoint: "https://push.example/2" } },
  ]);
  const webPushSender = fakeSender();
  const res = await sendPushToRecipient(
    { deviceTokenRepository, webPushSender },
    { recipientId: "u1", senderId: "u2", body: "hi there", conversationId: "c1" }
  );
  assert.equal(res.sent, 2);
  assert.equal(webPushSender.calls[0].payload.body, "hi there");
  assert.equal(webPushSender.calls[0].payload.data.conversationId, "c1");
});

test("prunes a subscription the push service reports as gone", async () => {
  const deviceTokenRepository = fakeRepo([
    { userId: "u1", deviceId: "d1", platform: "web", subscription: webSub },
  ]);
  const webPushSender = fakeSender(() => ({ ok: false, gone: true }));
  const res = await sendPushToRecipient(
    { deviceTokenRepository, webPushSender },
    { recipientId: "u1", body: "x" }
  );
  assert.equal(res.pruned, 1);
  assert.equal(deviceTokenRepository.rows.length, 0, "dead token removed");
});

test("skips non-web platforms (APNs/FCM not built yet)", async () => {
  const deviceTokenRepository = fakeRepo([
    { userId: "u1", deviceId: "phone", platform: "ios", subscription: { token: "apns-tok" } },
  ]);
  const webPushSender = fakeSender();
  const res = await sendPushToRecipient(
    { deviceTokenRepository, webPushSender },
    { recipientId: "u1", body: "x" }
  );
  assert.equal(res.skipped, 1);
  assert.equal(res.sent, 0);
  assert.equal(webPushSender.calls.length, 0);
});

test("recipient with no devices is a no-op", async () => {
  const res = await sendPushToRecipient(
    { deviceTokenRepository: fakeRepo(), webPushSender: fakeSender() },
    { recipientId: "nobody", body: "x" }
  );
  assert.deepEqual(res, { devices: 0, sent: 0, pruned: 0, skipped: 0, failed: 0, errors: [] });
});
