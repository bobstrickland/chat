import express from "express";
import {
  getDependencies,
  getMessageConsumerConfig,
  getIndexConsumerConfig,
} from "../config.js";
import { search } from "../core/search.js";
import { indexMessage } from "../core/indexMessage.js";
import { indexProfile } from "../core/indexProfile.js";
import { createIndexConsumer } from "../clients/kafkaConsumer.js";

const app = express();
app.use(express.json());

const deps = getDependencies();

async function authenticate(req, res, next) {
  const header = req.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : null;
  if (!token) return res.status(401).json({ error: "missing bearer token" });
  try {
    req.claims = await deps.verifyToken(token);
    req.bearerToken = token; // reused to call Messaging for the caller's conversations
    next();
  } catch {
    res.status(401).json({ error: "invalid token" });
  }
}

// GET /search?q=...&type=messages|users|all
app.get("/search", authenticate, async (req, res) => {
  try {
    const results = await search(deps, {
      q: String(req.query.q ?? ""),
      type: req.query.type ? String(req.query.type) : "all",
      bearerToken: req.bearerToken,
    });
    res.json(results);
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(`[search] query failed: ${err.message}`);
    res.status(500).json({ error: "search failed" });
  }
});

app.get("/health", (_req, res) => res.status(200).json({ status: "ok" }));

const port = process.env.PORT ?? 3000;

// Create the indices before serving/consuming (idempotent). If OpenSearch isn't
// up yet, keep retrying — the container may start before OpenSearch is ready.
async function ensureIndicesWithRetry(attempt = 0) {
  try {
    await deps.openSearch.ensureIndices();
  } catch (err) {
    if (attempt >= 30) throw err;
    // eslint-disable-next-line no-console
    console.log(`[search] OpenSearch not ready (${err.message}); retrying…`);
    await new Promise((r) => setTimeout(r, 2000));
    return ensureIndicesWithRetry(attempt + 1);
  }
}

ensureIndicesWithRetry()
  .then(() => {
    app.listen(port, () => {
      // eslint-disable-next-line no-console
      console.log(`search-service (httpServer adapter) listening on :${port}`);
    });

    // Two indexing consumers alongside the API (in AWS: MSK-triggered Lambdas
    // over the same indexMessage / indexProfile cores).
    createIndexConsumer({
      ...getMessageConsumerConfig(),
      handler: (event) => indexMessage(deps, event),
    })
      .start()
      .catch((err) => console.error(`[search] message consumer failed: ${err.message}`));

    createIndexConsumer({
      ...getIndexConsumerConfig(),
      handler: (event) => indexProfile(deps, event),
    })
      .start()
      .catch((err) => console.error(`[search] index consumer failed: ${err.message}`));
  })
  .catch((err) => {
    // eslint-disable-next-line no-console
    console.error(`[search] could not initialize OpenSearch indices: ${err.message}`);
    process.exit(1);
  });
