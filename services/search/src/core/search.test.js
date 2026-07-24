import { test } from "node:test";
import assert from "node:assert/strict";
import { search } from "./search.js";
import { indexMessage } from "./indexMessage.js";
import { indexProfile } from "./indexProfile.js";

/** In-memory OpenSearch stand-in — records what it was asked to index/search. */
function fakeOpenSearch() {
  return {
    messages: [],
    profiles: [],
    lastMessageQuery: null,
    async indexMessage(doc) {
      this.messages.push(doc);
    },
    async indexProfile(doc) {
      this.profiles.push(doc);
    },
    async searchMessages(q, conversationIds) {
      this.lastMessageQuery = { q, conversationIds };
      // echo back the ids so tests can assert the scope that was applied
      return conversationIds.map((id) => ({ conversationId: id, body: q }));
    },
    async searchProfiles(q) {
      return [{ userId: "u1", displayName: q }];
    },
  };
}

test("message search is scoped to the caller's conversations", async () => {
  const openSearch = fakeOpenSearch();
  const messagingClient = { async conversationIdsFor() {
    return ["dm#a#b", "grp#1"];
  } };

  const res = await search({ openSearch, messagingClient }, { q: "hello", bearerToken: "t" });

  assert.deepEqual(openSearch.lastMessageQuery.conversationIds, ["dm#a#b", "grp#1"]);
  assert.equal(res.messages.length, 2);
});

test("a user with no conversations matches no messages", async () => {
  const openSearch = fakeOpenSearch();
  const messagingClient = { async conversationIdsFor() {
    return [];
  } };

  const res = await search({ openSearch, messagingClient }, { q: "hello", bearerToken: "t" });
  assert.deepEqual(res.messages, []);
});

test("type=users skips the messages slice entirely", async () => {
  const openSearch = fakeOpenSearch();
  let called = false;
  const messagingClient = { async conversationIdsFor() {
    called = true;
    return ["x"];
  } };

  const res = await search({ openSearch, messagingClient }, { q: "bob", type: "users", bearerToken: "t" });
  assert.equal(called, false, "no membership lookup when not searching messages");
  assert.equal(openSearch.lastMessageQuery, null);
  assert.equal(res.users.length, 1);
});

test("a blank query returns empty without touching OpenSearch", async () => {
  const openSearch = fakeOpenSearch();
  const messagingClient = { async conversationIdsFor() {
    throw new Error("should not be called");
  } };
  const res = await search({ openSearch, messagingClient }, { q: "   ", bearerToken: "t" });
  assert.deepEqual(res, { messages: [], users: [] });
});

test("media-only messages (blank body) are not indexed", async () => {
  const openSearch = fakeOpenSearch();
  const r = await indexMessage(
    { openSearch },
    { messageId: "m1", conversationId: "c1", senderId: "s", body: "  ", sentAt: "t" },
  );
  assert.equal(r.indexed, false);
  assert.equal(openSearch.messages.length, 0);
});

test("a text message is indexed with a trimmed body", async () => {
  const openSearch = fakeOpenSearch();
  const r = await indexMessage(
    { openSearch },
    { messageId: "m1", conversationId: "c1", senderId: "s", body: " hi ", sentAt: "t" },
  );
  assert.equal(r.indexed, true);
  assert.equal(openSearch.messages[0].body, "hi");
});

test("only kind=profile events are indexed as profiles", async () => {
  const openSearch = fakeOpenSearch();
  assert.equal((await indexProfile({ openSearch }, { kind: "other", userId: "u" })).indexed, false);
  const r = await indexProfile({ openSearch }, { kind: "profile", userId: "u", displayName: "Bo" });
  assert.equal(r.indexed, true);
  assert.equal(openSearch.profiles[0].userId, "u");
});
