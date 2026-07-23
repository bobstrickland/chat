/**
 * Profile Service tests.
 *
 * core/ is tested with a plain in-memory fake — no AWS, no network. That's
 * only possible because core/ takes its dependencies as an argument and never
 * imports a client itself (CLAUDE.md core/adapters/clients split); if these
 * tests ever need a real AWS SDK, that rule has been broken.
 *
 * The repository tests run against DynamoDB Local and skip cleanly when it
 * isn't reachable, so `npm test` stays green without the stack.
 */
import test from "node:test";
import assert from "node:assert/strict";

import { provisionProfile } from "../src/core/provisionProfile.js";
import { getProfile } from "../src/core/getProfile.js";
import { updateProfile } from "../src/core/updateProfile.js";
import { deleteProfile } from "../src/core/deleteProfile.js";
import { createDynamoClient } from "../src/clients/dynamoClient.js";
import { createProfileRepository } from "../src/clients/profileRepository.js";

/** Minimal stand-in for profileRepository. */
function fakeRepo(seed = {}) {
  const rows = new Map(Object.entries(seed));
  return {
    rows,
    async createIfAbsent({ userId, displayName }) {
      if (rows.has(userId)) return { created: false, profile: rows.get(userId) };
      const now = new Date().toISOString();
      const profile = { userId, displayName, avatarUrl: null, bio: null, createdAt: now, updatedAt: now };
      rows.set(userId, profile);
      return { created: true, profile };
    },
    async get({ userId }) {
      return rows.get(userId) ?? null;
    },
    async update({ userId, fields }) {
      if (!rows.has(userId)) {
        throw Object.assign(new Error("nope"), { name: "ConditionalCheckFailedException" });
      }
      const next = { ...rows.get(userId), ...fields, updatedAt: new Date().toISOString() };
      rows.set(userId, next);
      return next;
    },
    async remove({ userId }) {
      rows.delete(userId);
    },
  };
}

// ---------------------------------------------------------------------------
// provisioning
// ---------------------------------------------------------------------------

test("provision derives displayName from the email local-part", async () => {
  const profileRepository = fakeRepo();
  const { created, profile } = await provisionProfile(
    { profileRepository },
    { userId: "u1", email: "ada.lovelace@example.com" }
  );
  assert.equal(created, true);
  assert.equal(profile.displayName, "ada.lovelace");
});

test("provision falls back when email is missing or malformed", async () => {
  for (const email of [undefined, "", "not-an-email", null]) {
    const profileRepository = fakeRepo();
    const { profile } = await provisionProfile({ profileRepository }, { userId: "u1", email });
    assert.equal(profile.displayName, "New User", `email=${JSON.stringify(email)}`);
  }
});

test("provision is idempotent and never clobbers an edited profile", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "u1", email: "first@example.com" });
  await updateProfile({ profileRepository }, { userId: "u1", callerUserId: "u1", fields: { displayName: "Chosen Name" } });

  // Cognito retries the trigger — must not overwrite.
  const second = await provisionProfile({ profileRepository }, { userId: "u1", email: "first@example.com" });
  assert.equal(second.created, false);
  assert.equal(second.profile.displayName, "Chosen Name");
});

test("provision requires a userId", async () => {
  await assert.rejects(() => provisionProfile({ profileRepository: fakeRepo() }, {}), /userId is required/);
});

// ---------------------------------------------------------------------------
// authorization — the rules that must hold identically on HTTP and Lambda
// ---------------------------------------------------------------------------

test("any authenticated user may read any profile", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "owner", email: "o@example.com" });

  const seen = await getProfile({ profileRepository }, { userId: "owner", callerUserId: "someone-else" });
  assert.equal(seen.userId, "owner");
});

test("unauthenticated reads are rejected", async () => {
  await assert.rejects(
    () => getProfile({ profileRepository: fakeRepo() }, { userId: "owner", callerUserId: null }),
    /unauthenticated/
  );
});

test("a user cannot update another user's profile", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "owner", email: "o@example.com" });

  await assert.rejects(
    () => updateProfile({ profileRepository }, { userId: "owner", callerUserId: "attacker", fields: { displayName: "x" } }),
    (err) => err.code === "FORBIDDEN"
  );
});

test("a user cannot delete another user's profile", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "owner", email: "o@example.com" });

  await assert.rejects(
    () => deleteProfile({ profileRepository }, { userId: "owner", callerUserId: "attacker" }),
    (err) => err.code === "FORBIDDEN"
  );
  assert.ok(await profileRepository.get({ userId: "owner" }), "profile must survive");
});

// ---------------------------------------------------------------------------
// update validation
// ---------------------------------------------------------------------------

test("update ignores server-owned fields", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "u1", email: "a@example.com" });

  const updated = await updateProfile(
    { profileRepository },
    { userId: "u1", callerUserId: "u1", fields: { displayName: "Legit", userId: "hijack", createdAt: "1970-01-01" } }
  );
  assert.equal(updated.userId, "u1");
  assert.notEqual(updated.createdAt, "1970-01-01");
});

test("update trims whitespace and rejects a blank displayName", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "u1", email: "a@example.com" });

  const updated = await updateProfile({ profileRepository }, { userId: "u1", callerUserId: "u1", fields: { displayName: "  Bob  " } });
  assert.equal(updated.displayName, "Bob");

  await assert.rejects(
    () => updateProfile({ profileRepository }, { userId: "u1", callerUserId: "u1", fields: { displayName: "   " } }),
    /cannot be blank/
  );
});

test("update enforces length limits and types", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "u1", email: "a@example.com" });
  const call = (fields) => updateProfile({ profileRepository }, { userId: "u1", callerUserId: "u1", fields });

  await assert.rejects(() => call({ displayName: "x".repeat(65) }), /exceeds 64/);
  await assert.rejects(() => call({ bio: "x".repeat(513) }), /exceeds 512/);
  await assert.rejects(() => call({ bio: 42 }), /must be a string or null/);
  await assert.rejects(() => call({}), /no updatable fields/);
});

test("update allows null to clear an optional field", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "u1", email: "a@example.com" });
  await updateProfile({ profileRepository }, { userId: "u1", callerUserId: "u1", fields: { bio: "something" } });

  const cleared = await updateProfile({ profileRepository }, { userId: "u1", callerUserId: "u1", fields: { bio: null } });
  assert.equal(cleared.bio, null);
});

test("updating a missing profile reports NOT_FOUND", async () => {
  await assert.rejects(
    () => updateProfile({ profileRepository: fakeRepo() }, { userId: "ghost", callerUserId: "ghost", fields: { bio: "x" } }),
    (err) => err.code === "NOT_FOUND"
  );
});

test("reading a missing profile reports NOT_FOUND", async () => {
  await assert.rejects(
    () => getProfile({ profileRepository: fakeRepo() }, { userId: "ghost", callerUserId: "someone" }),
    (err) => err.code === "NOT_FOUND"
  );
});

// ---------------------------------------------------------------------------
// repository — real DynamoDB Local
// ---------------------------------------------------------------------------

process.env.AWS_REGION ??= "us-east-1";
process.env.AWS_ACCESS_KEY_ID ??= "local";
process.env.AWS_SECRET_ACCESS_KEY ??= "local";
process.env.DYNAMODB_ENDPOINT ??= "http://localhost:8000";
process.env.PROFILES_TABLE ??= "profiles-local";

const repo = createProfileRepository(
  createDynamoClient({ region: process.env.AWS_REGION, endpoint: process.env.DYNAMODB_ENDPOINT }),
  process.env.PROFILES_TABLE
);

const reachable = await repo.get({ userId: "__probe__" }).then(() => true).catch(() => false);
const opts = reachable ? {} : { skip: `DynamoDB Local unreachable at ${process.env.DYNAMODB_ENDPOINT}` };

test("createIfAbsent is conditional against real DynamoDB", opts, async () => {
  const userId = `it-${Date.now()}`;
  const first = await repo.createIfAbsent({ userId, displayName: "First" });
  assert.equal(first.created, true);

  const second = await repo.createIfAbsent({ userId, displayName: "Second" });
  assert.equal(second.created, false, "condition must prevent overwrite");
  assert.equal(second.profile.displayName, "First");

  await repo.remove({ userId });
  assert.equal(await repo.get({ userId }), null);
});

test("update refuses to resurrect a deleted profile", opts, async () => {
  const userId = `it-gone-${Date.now()}`;
  await assert.rejects(
    () => repo.update({ userId, fields: { bio: "ghost" } }),
    (err) => err.name === "ConditionalCheckFailedException"
  );
  assert.equal(await repo.get({ userId }), null, "must not create a partial row");
});
