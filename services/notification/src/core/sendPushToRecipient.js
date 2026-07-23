/**
 * Handles a notification.trigger event: push the message to every registered
 * device of the offline recipient.
 *
 * Platform branches (CLAUDE.md: `platform` drives the mechanism):
 *   - web  → Web Push / VAPID (implemented)
 *   - ios/android → APNs/FCM (deferred to the mobile phase — logged and skipped)
 *
 * A dead web subscription (push endpoint 404/410) is pruned so we stop trying it.
 *
 * @param {{ deviceTokenRepository: object, webPushSender: object }} deps
 * @param {{ recipientId: string, senderId: string, body: string, conversationId: string }} event
 * @returns {Promise<{ devices:number, sent:number, pruned:number, skipped:number, failed:number, errors:string[] }>}
 */
export async function sendPushToRecipient({ deviceTokenRepository, webPushSender }, event) {
  if (!event.recipientId) {
    throw new Error("recipientId is required");
  }

  const devices = await deviceTokenRepository.listForUser(event.recipientId);
  const payload = {
    title: "New message",
    body: preview(event.body),
    data: { conversationId: event.conversationId, senderId: event.senderId },
  };

  let sent = 0;
  let pruned = 0;
  let skipped = 0;
  let failed = 0;
  const errors = [];

  for (const device of devices) {
    if (device.platform !== "web") {
      // APNs/FCM path not built yet.
      skipped += 1;
      continue;
    }
    const result = await webPushSender.send(device.subscription, payload);
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

/** Keep the push body short — it shows in a system notification, not a chat pane. */
function preview(body) {
  if (!body) return "You have a new message";
  return body.length > 120 ? body.slice(0, 117) + "…" : body;
}
