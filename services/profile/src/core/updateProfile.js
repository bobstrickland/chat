/**
 * Field validation lives in core/ (not the adapter) because these are
 * authorization/shape *rules* that must hold identically over HTTP and Lambda.
 *
 * Each editable field declares how to validate + normalize it. Anything not
 * listed here is server-owned and silently ignored (e.g. userId, timestamps).
 */
const VISIBILITIES = ["PUBLIC", "CONTACTS", "PRIVATE"];
const MAX_LINKS = 10;
const MAX_TAGS = 10;

const FIELDS = {
  displayName: strField({ max: 64, blankOk: false }),
  bio: strField({ max: 512, blankOk: true, nullable: true }),
  avatarMediaId: strField({ max: 128, blankOk: true, nullable: true }),
  phone: phoneField(),
  links: listField({ max: MAX_LINKS, itemMax: 512, kind: "url" }),
  tags: listField({ max: MAX_TAGS, itemMax: 32, kind: "tag" }),
  visibility: enumField(VISIBILITIES),
};

/**
 * Users may only modify their own profile.
 *
 * @param {{ profileRepository: object, searchIndexPublisher?: object }} deps
 * @param {{ userId: string, callerUserId: string, fields: object }} input
 */
export async function updateProfile({ profileRepository, searchIndexPublisher }, input) {
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
  for (const [key, validate] of Object.entries(FIELDS)) {
    if (input.fields?.[key] === undefined) continue;
    fields[key] = validate(key, input.fields[key]);
  }

  if (Object.keys(fields).length === 0) {
    throw new Error(`no updatable fields supplied (allowed: ${Object.keys(FIELDS).join(", ")})`);
  }

  try {
    const updated = await profileRepository.update({ userId: input.userId, fields });
    // Re-index for people-search (best-effort; never blocks the write result).
    // The publisher decides indexing vs removal based on the profile's visibility.
    await searchIndexPublisher?.publishProfile(updated);
    return updated;
  } catch (err) {
    if (err.name === "ConditionalCheckFailedException") {
      const notFound = new Error("profile not found");
      notFound.code = "NOT_FOUND";
      throw notFound;
    }
    throw err;
  }
}

/** A string field: `null` clears (when nullable); otherwise trimmed + length-checked. */
function strField({ max, blankOk, nullable = false }) {
  return (key, value) => {
    if (value === null) {
      if (!nullable) throw new Error(`${key} cannot be null`);
      return null;
    }
    if (typeof value !== "string") throw new Error(`${key} must be a string or null`);
    const trimmed = value.trim();
    if (trimmed.length > max) throw new Error(`${key} exceeds ${max} characters`);
    if (!blankOk && trimmed === "") throw new Error(`${key} cannot be blank`);
    return trimmed;
  };
}

/** Phone: null clears; otherwise digits + common separators, ≤32 chars, ≥3 digits. */
function phoneField() {
  return (key, value) => {
    if (value === null) return null;
    if (typeof value !== "string") throw new Error(`${key} must be a string or null`);
    const trimmed = value.trim();
    if (trimmed === "") return null;
    if (trimmed.length > 32) throw new Error(`${key} exceeds 32 characters`);
    if (!/^[+\d][\d\s().-]*$/.test(trimmed) || (trimmed.match(/\d/g) ?? []).length < 3) {
      throw new Error(`${key} is not a valid phone number`);
    }
    return trimmed;
  };
}

/** A bounded list of trimmed strings. `null`/`[]` clears it. */
function listField({ max, itemMax, kind }) {
  return (key, value) => {
    if (value === null) return [];
    if (!Array.isArray(value)) throw new Error(`${key} must be an array`);
    if (value.length > max) throw new Error(`${key} allows at most ${max} entries`);
    const out = [];
    for (const raw of value) {
      if (typeof raw !== "string") throw new Error(`each ${key} entry must be a string`);
      const item = raw.trim();
      if (item === "") continue; // drop blanks rather than erroring on a stray comma
      if (item.length > itemMax) throw new Error(`a ${key} entry exceeds ${itemMax} characters`);
      if (kind === "url" && !/^https?:\/\/\S+$/i.test(item)) {
        throw new Error(`each link must be an http(s) URL`);
      }
      out.push(item);
    }
    return out;
  };
}

function enumField(allowed) {
  return (key, value) => {
    if (!allowed.includes(value)) {
      throw new Error(`${key} must be one of ${allowed.join(", ")}`);
    }
    return value;
  };
}
