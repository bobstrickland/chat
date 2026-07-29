/**
 * Unit tests for full account deletion (Phase 12.5).
 *
 * core/deleteAccount orchestrates three collaborators (Profile service, identity
 * provider, Users ledger). It takes them all as deps, so it's tested with plain
 * in-memory fakes — no AWS, no network, no running stack. The things that matter
 * are ORDERING (Profile cleanup before the identity is destroyed) and the
 * BEST-EFFORT contract (a Profile/ledger failure must not block the deletion).
 */
import test from "node:test";
import assert from "node:assert/strict";

import { deleteAccount } from "../src/core/deleteAccount.js";

/** Records the order calls happen in, so we can assert the finalizer runs last. */
function harness({ profileThrows = false, providerEmail = "me@example.com" } = {}) {
  const calls = [];
  const deps = {
    profileService: {
      async deleteProfileAsUser({ userId, accessToken }) {
        calls.push("profile");
        assert.ok(accessToken, "profile delete must get the access token");
        if (profileThrows) throw new Error("profile service down");
        return { deleted: true, userId };
      },
    },
    identityProvider: {
      async deleteAccount({ accessToken }) {
        calls.push("identity");
        assert.ok(accessToken, "identity delete must get the access token");
        return { email: providerEmail };
      },
    },
    userRepository: {
      removed: [],
      async remove({ email }) {
        calls.push("ledger");
        this.userRepositoryEmail = email;
        deps.userRepository.removed.push(email);
      },
    },
  };
  return { deps, calls };
}

test("deletes profile, identity, then ledger — in that order", async () => {
  const { deps, calls } = harness();
  const result = await deleteAccount(deps, { userId: "u1", accessToken: "tok" });

  assert.deepEqual(result, { deleted: true, userId: "u1" });
  assert.deepEqual(calls, ["profile", "identity", "ledger"]);
  assert.deepEqual(deps.userRepository.removed, ["me@example.com"]);
});

test("a Profile failure does not block account deletion (best-effort)", async () => {
  const { deps, calls } = harness({ profileThrows: true });
  const result = await deleteAccount(deps, { userId: "u1", accessToken: "tok" });

  assert.equal(result.deleted, true);
  // Identity + ledger still ran even though profile cleanup threw.
  assert.deepEqual(calls, ["profile", "identity", "ledger"]);
});

test("skips the ledger delete when the provider returns no email", async () => {
  const { deps, calls } = harness({ providerEmail: null });
  await deleteAccount(deps, { userId: "u1", accessToken: "tok" });

  assert.deepEqual(calls, ["profile", "identity"]);
  assert.deepEqual(deps.userRepository.removed, []);
});

test("rejects an unauthenticated call before touching anything", async () => {
  const { deps, calls } = harness();
  await assert.rejects(
    () => deleteAccount(deps, { userId: "u1", accessToken: "" }),
    /unauthenticated/
  );
  assert.deepEqual(calls, [], "nothing should run without a token");
});
