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

  return deviceTokenRepository.upsert({
    userId: input.userId,
    deviceId: input.deviceId,
    platform: input.platform,
    subscription: input.subscription,
  });
}
