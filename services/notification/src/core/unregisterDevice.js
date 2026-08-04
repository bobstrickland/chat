/**
 * Removes a device registration — called by the client on sign-out.
 *
 * Without this, a device stays registered to whoever signed in on it first: sign
 * out, sign in as someone else, and the FIRST user's offline pushes still land on
 * that phone (a new row is written for the new user, but the old one is never
 * removed). Pruning only happens when a token goes dead, and the token is still
 * perfectly alive here — so the client has to say so explicitly.
 *
 * Self-only: the userId comes from the verified token, never the request body, so
 * a caller can only unregister their own device.
 *
 * Idempotent — deleting an already-absent row is a success, so a client that
 * retries (or signs out twice) doesn't need to care.
 *
 * @param {{ deviceTokenRepository: object }} deps
 * @param {{ userId: string, deviceId: string }} input
 */
export async function unregisterDevice({ deviceTokenRepository }, input) {
  if (!input.userId) {
    throw new Error("unauthenticated");
  }
  if (!input.deviceId) {
    throw new Error("deviceId is required");
  }
  await deviceTokenRepository.remove({ userId: input.userId, deviceId: input.deviceId });
  return { deviceId: input.deviceId, removed: true };
}
