/**
 * Mode→config mapping, tested without a broker. Small but worth it: the shape
 * kafkajs expects (`ssl` + a `sasl.oauthBearerProvider` returning `{ value }`)
 * is easy to get subtly wrong, and a mistake only shows up at connect time —
 * in AWS, on deploy.
 */
import test from "node:test";
import assert from "node:assert/strict";

import { kafkaAuthOptions } from "../src/clients/kafkaAuth.js";

test("plaintext (and unset) adds no transport config at all", () => {
  assert.deepEqual(kafkaAuthOptions("plaintext"), {});
  assert.deepEqual(kafkaAuthOptions(undefined), {}, "unset must behave as plaintext");
  assert.deepEqual(kafkaAuthOptions(""), {});
});

test("an unrecognised mode is treated as plaintext, never half-configured", () => {
  assert.deepEqual(kafkaAuthOptions("IAM_MAYBE"), {});
  assert.deepEqual(kafkaAuthOptions("sasl_ssl"), {});
});

test("iam turns on TLS + SASL/OAUTHBEARER", () => {
  const opts = kafkaAuthOptions("iam", "us-east-1");
  assert.equal(opts.ssl, true, "MSK requires TLS");
  assert.equal(opts.sasl.mechanism, "oauthbearer");
  assert.equal(typeof opts.sasl.oauthBearerProvider, "function");
});

test("iam is case- and whitespace-insensitive", () => {
  // So `KAFKA_AUTH=IAM` or a stray trailing space in .env still works.
  for (const v of ["IAM", " iam ", "Iam"]) {
    assert.equal(kafkaAuthOptions(v, "us-east-1").ssl, true, `mode ${JSON.stringify(v)}`);
  }
});

test("the token provider returns kafkajs's { value } shape", async () => {
  // Exercises the real signer. It computes a presigned SigV4 URL locally — no
  // network, no live MSK — but it does need credentials in the environment, so
  // supply dummies rather than depending on the machine's AWS config.
  const saved = { ...process.env };
  Object.assign(process.env, {
    AWS_ACCESS_KEY_ID: "AKIAIOSFODNN7EXAMPLE",
    AWS_SECRET_ACCESS_KEY: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    AWS_REGION: "us-east-1",
  });
  try {
    const { sasl } = kafkaAuthOptions("iam", "us-east-1");
    const result = await sasl.oauthBearerProvider();
    assert.equal(typeof result.value, "string");
    assert.ok(result.value.length > 0, "a token was produced");
  } finally {
    for (const k of Object.keys(process.env)) if (!(k in saved)) delete process.env[k];
    Object.assign(process.env, saved);
  }
});
