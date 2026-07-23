/**
 * Integration test for the postConfirmation trigger against DynamoDB Local.
 *
 * This path cannot be exercised through Cognito yet — the trigger is not
 * attached to the dev User Pool (`lambda_config` is empty), so nothing else
 * proves the reconciliation behaviour that core/register.js relies on when
 * its own Users write fails.
 *
 * Requires: dynamodb-local up + `scripts/init_dynamodb.sh` run.
 * Skips (rather than fails) when DynamoDB Local isn't reachable, so
 * `npm test` stays green on a machine with no stack running.
 */
import test from "node:test";
import assert from "node:assert/strict";

process.env.AWS_REGION ??= "us-east-1";
process.env.AWS_ACCESS_KEY_ID ??= "local";
process.env.AWS_SECRET_ACCESS_KEY ??= "local";
process.env.DYNAMODB_ENDPOINT ??= "http://localhost:8000";
process.env.USERS_TABLE ??= "users-local";
process.env.AUTH_PROVIDER ??= "local";
// LocalAuthProvider has no fallback signing key by design (a hardcoded default
// would be a token-forging key published in a public repo), so tests must
// supply one. Not a secret — this value signs nothing outside this process.
process.env.LOCAL_JWT_SIGNING_SECRET ??= "test-only-signing-secret";

const { createDynamoClient } = await import("../src/clients/dynamoClient.js");
const { createUserRepository } = await import("../src/clients/userRepository.js");
const { postConfirmation } = await import("../src/adapters/cognitoTriggers.js");

const repo = createUserRepository(
  createDynamoClient({
    region: process.env.AWS_REGION,
    endpoint: process.env.DYNAMODB_ENDPOINT,
  }),
  process.env.USERS_TABLE
);

const reachable = await repo
  .getUser({ email: "__probe__" })
  .then(() => true)
  .catch(() => false);

const opts = reachable
  ? {}
  : { skip: `DynamoDB Local unreachable at ${process.env.DYNAMODB_ENDPOINT}` };

const event = (email, sub) => ({ request: { userAttributes: { email, sub } } });

// PROFILE_SERVICE_URL / PROFILE_INTERNAL_API_KEY are intentionally left unset
// here: these tests cover Auth's own half of postConfirmation, and Profile is
// a separate service that must not be a test dependency. Provisioning
// therefore fails on every case below and logs — that is the behaviour under
// test, asserted explicitly in "confirmation succeeds even when profile
// provisioning fails". Don't silence the log without keeping that guarantee.

test("flips an existing UNCONFIRMED row to CONFIRMED", opts, async () => {
  const email = `pc-existing-${Date.now()}@example.com`;
  await repo.putUser({ email, userId: "sub-1", status: "UNCONFIRMED" });

  await postConfirmation(event(email, "sub-1"));

  const row = await repo.getUser({ email });
  assert.equal(row.status, "CONFIRMED");
  assert.equal(row.userId, "sub-1");
});

test("creates the row when registration's write was lost", opts, async () => {
  const email = `pc-missing-${Date.now()}@example.com`;
  assert.equal(await repo.getUser({ email }), null, "precondition: row absent");

  await postConfirmation(event(email, "sub-2"));

  const row = await repo.getUser({ email });
  assert.ok(row, "reconciliation should create the missing row");
  assert.equal(row.status, "CONFIRMED");
  assert.equal(row.userId, "sub-2");
});

test("does not clobber userId or createdAt on re-confirm", opts, async () => {
  const email = `pc-idem-${Date.now()}@example.com`;
  await repo.putUser({ email, userId: "sub-3", status: "UNCONFIRMED" });
  const before = await repo.getUser({ email });

  await postConfirmation(event(email, "sub-3"));
  await postConfirmation(event(email, "DIFFERENT-SUB"));

  const after = await repo.getUser({ email });
  assert.equal(after.userId, "sub-3", "userId must be write-once");
  assert.equal(after.createdAt, before.createdAt, "createdAt must be preserved");
  assert.equal(after.status, "CONFIRMED");
});

test("echoes the event back for Cognito", opts, async () => {
  const e = event(`pc-echo-${Date.now()}@example.com`, "sub-4");
  assert.equal(await postConfirmation(e), e);
});

test("confirmation succeeds even when profile provisioning fails", opts, async () => {
  // A throwing postConfirmation fails the user's confirmation in Cognito, so
  // Profile being unreachable must never propagate. Provisioning is
  // unconfigured in this suite, which is exactly that failure mode.
  const email = `pc-degraded-${Date.now()}@example.com`;
  const e = event(email, "sub-5");

  const returned = await postConfirmation(e);

  assert.equal(returned, e, "event must still be echoed back to Cognito");
  const row = await repo.getUser({ email });
  assert.equal(row.status, "CONFIRMED", "Auth's own half must still commit");
});
