/**
 * Handles a notification.trigger event: push the message to every registered
 * device of the offline recipient.
 *
 * Platform branches (CLAUDE.md: `platform` drives the mechanism):
 *   - web     → Web Push / VAPID  (webPushSender)
 *   - android → FCM HTTP v1       (fcmSender)
 *   - ios     → APNs — no iOS client per the rules, so skipped
 *
 * A dead token — web push endpoint 404/410, or FCM reporting the registration
 * token unregistered — is pruned so we stop trying it. Both senders report that
 * the same way (`{ gone: true }`), which is why one loop covers both.
 *
 * @param {{ deviceTokenRepository: object, webPushSender: object, fcmSender?: object }} deps
 * @param {{ recipientId: string, senderId: string, body: string, conversationId: string, messageId?: string, sentAt?: string }} event
 * @returns {Promise<{ devices:number, sent:number, pruned:number, skipped:number, failed:number, errors:string[] }>}
 */
export async function sendPushToRecipient(
  { deviceTokenRepository, webPushSender, fcmSender },
  event
) {
  if (!event.recipientId) {
    throw new Error("recipientId is required");
  }

  const devices = await deviceTokenRepository.listForUser(event.recipientId);
  const payload = {
    title: "New message",
    body: preview(event.body),
    data: {
      conversationId: event.conversationId,
      senderId: event.senderId,
      messageId: event.messageId,
      sentAt: event.sentAt,
    },
  };

  let sent = 0;
  let pruned = 0;
  let skipped = 0;
  let failed = 0;
  const errors = [];

  for (const device of devices) {
    const sender = senderFor(device.platform, { webPushSender, fcmSender });
    if (!sender) {
      // iOS (no client), or android on a deployment with no FCM credentials.
      skipped += 1;
      continue;
    }
    const result = await sender.send(device.subscription, payload);
    if (result.ok) {
      sent += 1;
    } else if (result.gone) {
      await deviceTokenRepository.remove({ userId: device.userId, deviceId: device.deviceId });
      pruned += 1;
    } else {
      // A transient/other failure (endpoint error, bad payload) — count it so it
      // isn't silently swallowed, and surface the reason.
      failed += 1;
      errors.push(result.error ?? `status ${result.statusCode}`);
    }
  }

  return { devices: devices.length, sent, pruned, skipped, failed, errors };
}

/**
 * The sender for a platform, or null when that platform can't be pushed to right
 * now. An unconfigured FCM sender counts as "can't" — a missing Firebase project
 * is a deployment state, not a per-message error, so it must not inflate the
 * failure count on every trigger.
 */
function senderFor(platform, { webPushSender, fcmSender }) {
  if (platform === "web") return webPushSender ?? null;
  if (platform === "android") return fcmSender?.enabled ? fcmSender : null;
  return null; // ios — APNs not built
}

/** Keep the push body short — it shows in a system notification, not a chat pane. */
function preview(body) {
  if (!body) return "You have a new message";
  return body.length > 120 ? body.slice(0, 117) + "…" : body;
}
