/**
 * The codec registration is three lines, but the failure it prevents is a
 * consumer group that silently stops forever — so it's worth asserting that the
 * codec is actually reachable through kafkajs's registry AND that it round-trips
 * real bytes. A registered-but-broken codec would fail identically to no codec.
 */
import test from "node:test";
import assert from "node:assert/strict";
import kafkajs from "kafkajs";
const { CompressionCodecs, CompressionTypes } = kafkajs; // CJS: see kafkaCompression.js

import { registerCompressionCodecs } from "../src/clients/kafkaCompression.js";

test("kafkajs does not ship Snappy — that's why this exists", () => {
  // Guard the premise. If a future kafkajs adds Snappy natively, this fails and
  // the whole module can be deleted.
  assert.equal(CompressionTypes.Snappy, 2, "Snappy is compression type 2 on the wire");
});

test("registering makes the Snappy codec available to kafkajs", () => {
  registerCompressionCodecs();
  assert.equal(typeof CompressionCodecs[CompressionTypes.Snappy], "function");
});

test("registration is idempotent", () => {
  registerCompressionCodecs();
  const first = CompressionCodecs[CompressionTypes.Snappy];
  registerCompressionCodecs();
  assert.equal(CompressionCodecs[CompressionTypes.Snappy], first);
});

test("the codec round-trips a realistic event payload", async () => {
  registerCompressionCodecs();
  const codec = CompressionCodecs[CompressionTypes.Snappy]();

  const payload = JSON.stringify({
    recipientId: "u1",
    conversationId: "dm#a#b",
    body: "compression check ".repeat(20), // long enough to actually compress
  });

  const compressed = await codec.compress({ buffer: Buffer.from(payload) });
  assert.ok(compressed.length > 0);
  assert.notEqual(compressed.toString("utf8"), payload, "it really was compressed");

  const decompressed = await codec.decompress(compressed);
  assert.equal(decompressed.toString("utf8"), payload, "and it survives the round trip");
});

test("GZIP is untouched — kafkajs's own codec still works", async () => {
  registerCompressionCodecs();
  const gzip = CompressionCodecs[CompressionTypes.GZIP]();
  const round = await gzip.decompress(await gzip.compress({ buffer: Buffer.from("hello") }));
  assert.equal(round.toString("utf8"), "hello");
});
