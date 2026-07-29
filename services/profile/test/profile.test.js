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
import { getMyProfile } from "../src/core/getMyProfile.js";
import { updateProfile } from "../src/core/updateProfile.js";
import { deleteProfile } from "../src/core/deleteProfile.js";
import { addContact, removeContact, listContacts } from "../src/core/contacts.js";
import { createDynamoClient } from "../src/clients/dynamoClient.js";
import { createProfileRepository } from "../src/clients/profileRepository.js";
import { createContactRepository } from "../src/clients/contactRepository.js";

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

/** In-memory contactRepository. `pairs` are [owner, contact] tuples. */
function fakeContacts(pairs = []) {
  const set = new Set(pairs.map(([o, c]) => `${o}#${c}`));
  return {
    async add({ userId, contactId }) {
      set.add(`${userId}#${contactId}`);
    },
    async remove({ userId, contactId }) {
      set.delete(`${userId}#${contactId}`);
    },
    async isContact({ userId, contactId }) {
      return set.has(`${userId}#${contactId}`);
    },
    async list({ userId }) {
      return [...set]
        .filter((k) => k.startsWith(`${userId}#`))
        .map((k) => ({ contactId: k.split("#")[1], createdAt: "2026-01-01T00:00:00Z" }));
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
// lazy provisioning (getMyProfile) — covers users no trigger provisioned,
// notably federated (Google) users
// ---------------------------------------------------------------------------

test("getMyProfile creates a default profile when none exists", async () => {
  const profileRepository = fakeRepo();
  const profile = await getMyProfile({ profileRepository }, { userId: "u1", email: "grace@example.com" });
  assert.equal(profile.userId, "u1");
  assert.equal(profile.displayName, "grace");
  assert.ok(await profileRepository.get({ userId: "u1" }), "profile was persisted");
});

test("getMyProfile falls back to New User with no usable email (e.g. access token)", async () => {
  const profileRepository = fakeRepo();
  const profile = await getMyProfile({ profileRepository }, { userId: "google-user", email: null });
  assert.equal(profile.displayName, "New User");
});

test("getMyProfile returns the existing profile without clobbering edits", async () => {
  const profileRepository = fakeRepo();
  await getMyProfile({ profileRepository }, { userId: "u1", email: "a@example.com" });
  await updateProfile({ profileRepository }, { userId: "u1", callerUserId: "u1", fields: { displayName: "Chosen" } });

  const again = await getMyProfile({ profileRepository }, { userId: "u1", email: "a@example.com" });
  assert.equal(again.displayName, "Chosen", "second access must not overwrite the edited name");
});

test("getMyProfile requires a userId", async () => {
  await assert.rejects(() => getMyProfile({ profileRepository: fakeRepo() }, {}), /userId is required/);
});

// ---------------------------------------------------------------------------
// authorization — the rules that must hold identically on HTTP and Lambda
// ---------------------------------------------------------------------------

test("a PUBLIC profile is fully readable by anyone (with bio etc.)", async () => {
  const profileRepository = fakeRepo({
    owner: { userId: "owner", displayName: "Owner", bio: "hi", visibility: "PUBLIC" },
  });
  const seen = await getProfile(
    { profileRepository, contactRepository: fakeContacts() },
    { userId: "owner", callerUserId: "someone-else" },
  );
  assert.equal(seen.userId, "owner");
  assert.equal(seen.restricted, false);
  assert.equal(seen.bio, "hi");
});

test("a PRIVATE profile hides details from others (basic identity only)", async () => {
  const profileRepository = fakeRepo({
    owner: { userId: "owner", displayName: "Owner", bio: "secret", phone: "555", visibility: "PRIVATE" },
  });
  const seen = await getProfile(
    { profileRepository, contactRepository: fakeContacts() },
    { userId: "owner", callerUserId: "stranger" },
  );
  assert.equal(seen.restricted, true);
  assert.equal(seen.displayName, "Owner", "name still shown for chat rendering");
  assert.equal(seen.bio, undefined, "detail withheld");
  assert.equal(seen.phone, undefined, "detail withheld");
});

test("a CONTACTS profile is full only if the OWNER added the viewer", async () => {
  const profileRepository = fakeRepo({
    owner: { userId: "owner", displayName: "Owner", bio: "hey", visibility: "CONTACTS" },
  });
  // stranger: owner did NOT add them → restricted
  const asStranger = await getProfile(
    { profileRepository, contactRepository: fakeContacts([["owner", "friend"]]) },
    { userId: "owner", callerUserId: "stranger" },
  );
  assert.equal(asStranger.restricted, true);
  assert.equal(asStranger.bio, undefined);
  // friend: owner added them → full
  const asFriend = await getProfile(
    { profileRepository, contactRepository: fakeContacts([["owner", "friend"]]) },
    { userId: "owner", callerUserId: "friend" },
  );
  assert.equal(asFriend.restricted, false);
  assert.equal(asFriend.bio, "hey");
});

test("unauthenticated reads are rejected", async () => {
  await assert.rejects(
    () => getProfile({ profileRepository: fakeRepo() }, { userId: "owner", callerUserId: null }),
    /unauthenticated/
  );
});

// ---------------------------------------------------------------------------
// contacts (Phase 11)
// ---------------------------------------------------------------------------

test("addContact stores the pair and returns the target's identity", async () => {
  const profileRepository = fakeRepo({
    bob: { userId: "bob", displayName: "Bob", avatarMediaId: "m1", bio: "x" },
  });
  const contactRepository = fakeContacts();
  const added = await addContact({ profileRepository, contactRepository }, { callerUserId: "me", contactId: "bob" });
  assert.deepEqual(added, { userId: "bob", displayName: "Bob", avatarMediaId: "m1" });
  assert.equal(await contactRepository.isContact({ userId: "me", contactId: "bob" }), true);
});

test("addContact rejects self and unknown users", async () => {
  const deps = { profileRepository: fakeRepo(), contactRepository: fakeContacts() };
  await assert.rejects(() => addContact(deps, { callerUserId: "me", contactId: "me" }), /yourself/);
  await assert.rejects(
    () => addContact(deps, { callerUserId: "me", contactId: "ghost" }),
    (err) => err.code === "NOT_FOUND",
  );
});

test("listContacts enriches each contact with name + avatar", async () => {
  const profileRepository = fakeRepo({
    a: { userId: "a", displayName: "Ada", avatarMediaId: "av" },
  });
  const contactRepository = fakeContacts([["me", "a"]]);
  const { contacts } = await listContacts({ profileRepository, contactRepository }, { callerUserId: "me" });
  assert.equal(contacts.length, 1);
  assert.equal(contacts[0].displayName, "Ada");
  assert.equal(contacts[0].avatarMediaId, "av");
});

test("removeContact deletes the pair", async () => {
  const contactRepository = fakeContacts([["me", "a"]]);
  const r = await removeContact({ contactRepository }, { callerUserId: "me", contactId: "a" });
  assert.equal(r.removed, true);
  assert.equal(await contactRepository.isContact({ userId: "me", contactId: "a" }), false);
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

test("delete cascades: profile row + own contacts + search de-index (Phase 12.5)", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "me", email: "me@example.com" });
  // "me" added alice + bob; carol added "me" (a reverse contact).
  const contactRepository = fakeContacts([["me", "alice"], ["me", "bob"], ["carol", "me"]]);

  const deindexed = [];
  const searchIndexPublisher = { async publishProfileDeleted(id) { deindexed.push(id); } };

  const result = await deleteProfile(
    { profileRepository, contactRepository, searchIndexPublisher },
    { userId: "me", callerUserId: "me" }
  );

  assert.deepEqual(result, { deleted: true, userId: "me" });
  assert.equal(await profileRepository.get({ userId: "me" }), null, "profile row gone");
  assert.deepEqual(await contactRepository.list({ userId: "me" }), [], "own contacts gone");
  assert.deepEqual(deindexed, ["me"], "de-indexed from people-search");
  // Reverse contact left intact — degrades gracefully, per the design.
  assert.equal(
    await contactRepository.isContact({ userId: "carol", contactId: "me" }),
    true,
    "others' contact lists are left untouched"
  );
});

test("delete tolerates search/contacts cleanup failure (best-effort)", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "me", email: "me@example.com" });
  const contactRepository = fakeContacts([["me", "alice"]]);
  const searchIndexPublisher = {
    async publishProfileDeleted() { throw new Error("kafka down"); },
  };

  // The profile row must still be deleted even though de-index throws.
  const result = await deleteProfile(
    { profileRepository, contactRepository, searchIndexPublisher },
    { userId: "me", callerUserId: "me" }
  );
  assert.equal(result.deleted, true);
  assert.equal(await profileRepository.get({ userId: "me" }), null);
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

test("update accepts the Phase 10 fields and normalizes them", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "u1", email: "a@example.com" });
  const updated = await updateProfile(
    { profileRepository },
    {
      userId: "u1",
      callerUserId: "u1",
      fields: {
        avatarMediaId: "media-123",
        phone: " +1 (555) 123-4567 ",
        links: ["https://a.example", " ", "https://b.example"], // blank dropped
        tags: ["  Java  ", "chat"],
        visibility: "CONTACTS",
      },
    },
  );
  assert.equal(updated.avatarMediaId, "media-123");
  assert.equal(updated.phone, "+1 (555) 123-4567");
  assert.deepEqual(updated.links, ["https://a.example", "https://b.example"]);
  assert.deepEqual(updated.tags, ["Java", "chat"]);
  assert.equal(updated.visibility, "CONTACTS");
});

test("update enforces the Phase 10 limits and formats", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "u1", email: "a@example.com" });
  const call = (fields) => updateProfile({ profileRepository }, { userId: "u1", callerUserId: "u1", fields });

  await assert.rejects(() => call({ links: Array(11).fill("https://x.example") }), /at most 10/);
  await assert.rejects(() => call({ links: ["not-a-url"] }), /http\(s\) URL/);
  await assert.rejects(() => call({ tags: Array(11).fill("t") }), /at most 10/);
  await assert.rejects(() => call({ phone: "abc" }), /valid phone/);
  await assert.rejects(() => call({ visibility: "SECRET" }), /must be one of/);
});

test("update lets a user clear lists and phone", async () => {
  const profileRepository = fakeRepo();
  await provisionProfile({ profileRepository }, { userId: "u1", email: "a@example.com" });
  await updateProfile({ profileRepository }, { userId: "u1", callerUserId: "u1", fields: { tags: ["x"], phone: "5551212" } });
  const cleared = await updateProfile(
    { profileRepository },
    { userId: "u1", callerUserId: "u1", fields: { tags: null, phone: null } },
  );
  assert.deepEqual(cleared.tags, []);
  assert.equal(cleared.phone, null);
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

const contacts = createContactRepository(
  createDynamoClient({ region: process.env.AWS_REGION, endpoint: process.env.DYNAMODB_ENDPOINT }),
  process.env.CONTACTS_TABLE ?? "contacts-local"
);

test("contactRepository add / isContact / list / remove against real DynamoDB", opts, async () => {
  const me = `it-me-${Date.now()}`;
  const a = `${me}-a`;
  const b = `${me}-b`;

  await contacts.add({ userId: me, contactId: a });
  await contacts.add({ userId: me, contactId: b });

  assert.equal(await contacts.isContact({ userId: me, contactId: a }), true);
  assert.equal(await contacts.isContact({ userId: me, contactId: "nobody" }), false);

  const list = await contacts.list({ userId: me });
  assert.deepEqual(list.map((c) => c.contactId).sort(), [a, b].sort());

  await contacts.remove({ userId: me, contactId: a });
  assert.equal(await contacts.isContact({ userId: me, contactId: a }), false);
  await contacts.remove({ userId: me, contactId: b });
});
