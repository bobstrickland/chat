const PLATFORMS = ["web", "ios", "android"];

/**
 * Registers (or re-registers) a device for push. The caller's userId comes from
 * the verified token — a user can only register a device for themselves.
 *
 * @param {{ deviceTokenRepository: object }} deps
 * @param {{ userId: string, deviceId: string, platform: string, subscription: object }} input
 */
export async function registerDevice({ deviceTokenRepository }, input) {
  if (!input.userId) {
    throw new Error("unauthenticated");
  }
  if (!input.deviceId) {
    throw new Error("deviceId is required");
  }
  if (!PLATFORMS.includes(input.platform)) {
    throw new Error(`platform must be one of: ${PLATFORMS.join(", ")}`);
  }
  if (!input.subscription || typeof input.subscription !== "object") {
    throw new Error("subscription is required");
  }
  // A web subscription must carry an endpoint (where the push service lives).
  if (input.platform === "web" && !input.subscription.endpoint) {
    throw new Error("web subscription must include an endpoint");
  }
  // A mobile subscription is the platform's registration token (FCM/APNs).
  // Validated here rather than at send time so a broken client registration
  // fails loudly at the point it's made, not silently hours later on a push.
  if (
    (input.platform === "android" || input.platform === "ios") &&
    !input.subscription.token
  ) {
    throw new Error(`${input.platform} subscription must include a token`);
  }

  return deviceTokenRepository.upsert({
    userId: input.userId,
    deviceId: input.deviceId,
    platform: input.platform,
    subscription: input.subscription,
  });
}
