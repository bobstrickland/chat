/**
 * The FCM client is testable without Firebase because `fetch` is injectable and
 * the service-account key is just an RSA key — generated here per run. This
 * covers the parts we hand-rolled instead of taking firebase-admin for: the
 * OAuth2 JWT-bearer exchange, the v1 message shape, and dead-token detection.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, createVerify } from "node:crypto";

import { createFcmSender } from "../src/clients/fcmSender.js";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const PEM = privateKey.export({ type: "pkcs8", format: "pem" });

const config = {
  enabled: true,
  projectId: "chat-dev",
  clientEmail: "pusher@chat-dev.iam.gserviceaccount.com",
  privateKey: PEM,
};

/** Records every request; token exchanges succeed, FCM posts use `fcmReply`. */
function fakeFetch(fcmReply = { status: 200, body: { name: "projects/chat-dev/messages/1" } }) {
  const calls = [];
  const impl = async (url, options) => {
    calls.push({ url, options });
    if (url === "https://oauth2.googleapis.com/token") {
      return jsonResponse(200, { access_token: "ya29.fake", expires_in: 3600 });
    }
    return jsonResponse(fcmReply.status, fcmReply.body);
  };
  impl.calls = calls;
  return impl;
}

function jsonResponse(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

const payload = {
  title: "New message",
  body: "hi there",
  data: { conversationId: "dm#a#b", senderId: "u2", messageId: "m1", sentAt: undefined },
};

test("an unconfigured sender is disabled rather than throwing", async () => {
  for (const cfg of [{ enabled: false }, { enabled: true, projectId: "p" }]) {
    const sender = createFcmSender(cfg);
    assert.equal(sender.enabled, false);
    const res = await sender.send({ token: "t" }, payload);
    assert.equal(res.unconfigured, true);
  }
});

test("mints a service-account JWT and posts an FCM v1 message", async () => {
  const fetchImpl = fakeFetch();
  const sender = createFcmSender({ ...config, fetchImpl });
  const res = await sender.send({ token: "device-token" }, payload);
  assert.deepEqual(res, { ok: true });

  // 1. token exchange — a correctly signed jwt-bearer assertion
  const exchange = fetchImpl.calls[0];
  const form = new URLSearchParams(exchange.options.body);
  assert.equal(form.get("grant_type"), "urn:ietf:params:oauth:grant-type:jwt-bearer");
  const [h, c, sig] = form.get("assertion").split(".");
  const claims = JSON.parse(Buffer.from(c, "base64url").toString("utf8"));
  assert.equal(claims.iss, config.clientEmail);
  assert.equal(claims.aud, "https://oauth2.googleapis.com/token");
  assert.equal(claims.scope, "https://www.googleapis.com/auth/firebase.messaging");
  assert.ok(claims.exp > claims.iat, "expiry is in the future");
  assert.ok(
    createVerify("RSA-SHA256").update(`${h}.${c}`).verify(publicKey, Buffer.from(sig, "base64url")),
    "assertion is signed by the service-account key"
  );

  // 2. the send itself
  const send = fetchImpl.calls[1];
  assert.equal(send.url, "https://fcm.googleapis.com/v1/projects/chat-dev/messages:send");
  assert.equal(send.options.headers.authorization, "Bearer ya29.fake");
  const { message } = JSON.parse(send.options.body);
  assert.equal(message.token, "device-token");
  assert.equal(message.notification.body, "hi there");
  assert.equal(message.android.priority, "high");
  assert.equal(message.android.notification.channel_id, "messages");
  // FCM requires string data values, and rejects nulls.
  assert.deepEqual(message.data, { conversationId: "dm#a#b", senderId: "u2", messageId: "m1" });
});

test("reuses the access token across sends", async () => {
  const fetchImpl = fakeFetch();
  const sender = createFcmSender({ ...config, fetchImpl });
  await sender.send({ token: "t1" }, payload);
  await sender.send({ token: "t2" }, payload);
  const exchanges = fetchImpl.calls.filter((c) => c.url.includes("oauth2")).length;
  assert.equal(exchanges, 1, "second send reuses the cached token");
  assert.equal(fetchImpl.calls.length, 3);
});

test("reports an unregistered token as gone, so the caller prunes it", async () => {
  const dead = [
    { status: 404, body: { error: { status: "NOT_FOUND", message: "requested entity was not found" } } },
    { status: 403, body: { error: { status: "SENDER_ID_MISMATCH", message: "wrong sender" } } },
    { status: 400, body: { error: { status: "INVALID_ARGUMENT", message: "bad token" } } },
  ];
  for (const reply of dead) {
    const sender = createFcmSender({ ...config, fetchImpl: fakeFetch(reply) });
    const res = await sender.send({ token: "t" }, payload);
    assert.equal(res.gone, true, `status ${reply.status} should prune`);
  }
});

test("a server-side FCM error is a failure, not a prune", async () => {
  const sender = createFcmSender({
    ...config,
    fetchImpl: fakeFetch({ status: 503, body: { error: { status: "UNAVAILABLE", message: "try later" } } }),
  });
  const res = await sender.send({ token: "t" }, payload);
  assert.equal(res.gone, undefined);
  assert.equal(res.statusCode, 503);
  assert.match(res.error, /try later/);
});

test("a failed token exchange is surfaced as an error, not a throw", async () => {
  const fetchImpl = async (url) =>
    url.includes("oauth2")
      ? jsonResponse(400, { error: "invalid_grant", error_description: "clock skew" })
      : jsonResponse(200, {});
  const res = await createFcmSender({ ...config, fetchImpl }).send({ token: "t" }, payload);
  assert.equal(res.ok, false);
  assert.match(res.error, /clock skew/);
});

test("a subscription with no token is rejected without calling FCM", async () => {
  const fetchImpl = fakeFetch();
  const res = await createFcmSender({ ...config, fetchImpl }).send({}, payload);
  assert.equal(res.ok, false);
  assert.match(res.error, /no token/);
  assert.equal(fetchImpl.calls.length, 0);
});
