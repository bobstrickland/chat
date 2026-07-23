/** Fields a user may change. Anything not listed here is server-owned. */
const EDITABLE_FIELDS = ["displayName", "avatarUrl", "bio"];

const LIMITS = { displayName: 64, avatarUrl: 512, bio: 512 };

/**
 * Users may only modify their own profile. This check lives in core/ rather
 * than the adapter because it's an authorization *rule*, not a transport
 * concern — it must hold identically over HTTP and Lambda.
 *
 * @param {{ profileRepository: object }} deps
 * @param {{ userId: string, callerUserId: string, fields: object }} input
 */
export async function updateProfile({ profileRepository }, input) {
  if (!input.callerUserId) {
    throw new Error("unauthenticated");
  }
  if (!input.userId) {
    throw new Error("userId is required");
  }
  if (input.userId !== input.callerUserId) {
    const err = new Error("cannot modify another user's profile");
    err.code = "FORBIDDEN";
    throw err;
  }

  const fields = {};
  for (const key of EDITABLE_FIELDS) {
    if (input.fields?.[key] === undefined) continue;

    const value = input.fields[key];
    // Explicit null clears an optional field; anything else must be a string.
    if (value !== null && typeof value !== "string") {
      throw new Error(`${key} must be a string or null`);
    }
    if (typeof value === "string" && value.length > LIMITS[key]) {
      throw new Error(`${key} exceeds ${LIMITS[key]} characters`);
    }
    if (key === "displayName" && value !== null && value.trim() === "") {
      throw new Error("displayName cannot be blank");
    }
    fields[key] = typeof value === "string" ? value.trim() : value;
  }

  if (Object.keys(fields).length === 0) {
    throw new Error(`no updatable fields supplied (allowed: ${EDITABLE_FIELDS.join(", ")})`);
  }

  try {
    return await profileRepository.update({ userId: input.userId, fields });
  } catch (err) {
    if (err.name === "ConditionalCheckFailedException") {
      const notFound = new Error("profile not found");
      notFound.code = "NOT_FOUND";
      throw notFound;
    }
    throw err;
  }
}
