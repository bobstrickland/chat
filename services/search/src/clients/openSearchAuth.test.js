/**
 * Auth-mode mapping, tested without a domain. The signed path can't be verified
 * against real AWS here, but what IS testable matters: that local stays
 * unsigned, that a real deployment can't accidentally run unsigned, and that
 * misconfiguration fails at startup with a message naming the variable — rather
 * than as 403s that look like "search returns nothing".
 */
import test from "node:test";
import assert from "node:assert/strict";

import { openSearchAuthOptions } from "./openSearchAuth.js";

test("none (and unset) means plain HTTP — no transport override", () => {
  for (const mode of ["none", undefined, "", "  ", "NONE"]) {
    assert.deepEqual(openSearchAuthOptions(mode), {}, `mode=${JSON.stringify(mode)}`);
  }
});

test("iam returns a signing transport", () => {
  const opts = openSearchAuthOptions("iam", "us-east-1", "es");
  // AwsSigv4Signer works by swapping the Connection/Transport classes, so those
  // being present is the observable proof that signing is wired in.
  assert.equal(typeof opts.Connection, "function");
  assert.equal(typeof opts.Transport, "function");
});

test("iam is case- and whitespace-insensitive", () => {
  for (const v of ["IAM", " iam ", "Iam"]) {
    assert.equal(typeof openSearchAuthOptions(v, "us-east-1").Connection, "function", v);
  }
});

test("an unrecognised mode throws instead of quietly running unsigned", () => {
  assert.throws(() => openSearchAuthOptions("sigv4", "us-east-1"), /must be 'none' or 'iam'/);
  assert.throws(() => openSearchAuthOptions("aws", "us-east-1"), /OPENSEARCH_AUTH/);
});

test("iam without a region names the variable to set", () => {
  // The signer's own error is just "Region cannot be empty".
  assert.throws(() => openSearchAuthOptions("iam", undefined), /AWS_REGION is required/);
  assert.throws(() => openSearchAuthOptions("iam", "  "), /AWS_REGION is required/);
});

test("service accepts es and aoss, and rejects anything else", () => {
  // Wrong service = signature mismatch at request time, not a clear error, so
  // it's validated here.
  assert.equal(typeof openSearchAuthOptions("iam", "us-east-1", "aoss").Connection, "function");
  assert.equal(typeof openSearchAuthOptions("iam", "us-east-1", "ES").Connection, "function");
  assert.throws(() => openSearchAuthOptions("iam", "us-east-1", "opensearch"), /'es' or 'aoss'/);
});

test("service defaults to es (a provisioned domain)", () => {
  assert.equal(typeof openSearchAuthOptions("iam", "us-east-1", undefined).Connection, "function");
});
