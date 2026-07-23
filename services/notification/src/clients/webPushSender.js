import webpush from "web-push";

/**
 * Web Push (VAPID) sender — wraps the reference `web-push` library, which
 * handles the VAPID JWT and the RFC-8291 payload encryption. This is the whole
 * reason Notification is Node (see README): the library is mature and does the
 * cryptography correctly.
 *
 * A push endpoint that returns 404/410 means the subscription is dead (browser
 * unsubscribed / cleared data) — we surface that as `{ gone: true }` so the
 * caller can prune the token, rather than treating it as an error.
 */
export function createWebPushSender({ publicKey, privateKey, subject }) {
  if (!publicKey || !privateKey) {
    throw new Error("VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY are required");
  }
  webpush.setVapidDetails(subject || "mailto:dev@example.com", publicKey, privateKey);

  return {
    async send(subscription, payload) {
      try {
        await webpush.sendNotification(subscription, JSON.stringify(payload));
        return { ok: true };
      } catch (err) {
        if (err.statusCode === 404 || err.statusCode === 410) {
          return { ok: false, gone: true };
        }
        return { ok: false, error: err.message, statusCode: err.statusCode };
      }
    },
  };
}
