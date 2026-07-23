import express from "express";
import crypto from "node:crypto";
import { getDependencies, getInternalApiKey } from "../config.js";
import { provisionProfile } from "../core/provisionProfile.js";
import { getProfile } from "../core/getProfile.js";
import { getMyProfile } from "../core/getMyProfile.js";
import { updateProfile } from "../core/updateProfile.js";
import { deleteProfile } from "../core/deleteProfile.js";

const app = express();
app.use(express.json());

// Module-level bundle is a warm-process perf bonus only (Fargate target).
// Nothing here depends on reuse for correctness — per CLAUDE.md.
const deps = getDependencies();
const internalApiKey = getInternalApiKey();

/** Maps core/ error codes onto HTTP status. Transport concern, so it lives here. */
const STATUS = { NOT_FOUND: 404, FORBIDDEN: 403 };

function fail(res, err) {
  if (err.message === "unauthenticated") return res.status(401).json({ error: err.message });
  return res.status(STATUS[err.code] ?? 400).json({ error: err.message });
}

/**
 * Verifies the bearer token via JWKS and attaches normalized claims.
 * This service never calls Cognito — signatures are checked against public
 * keys only (CLAUDE.md "Cognito isolation").
 */
async function authenticate(req, res, next) {
  const header = req.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : null;
  if (!token) {
    return res.status(401).json({ error: "missing bearer token" });
  }
  try {
    req.claims = await deps.verifyToken(token);
    next();
  } catch {
    // Deliberately opaque: never leak why verification failed.
    res.status(401).json({ error: "invalid token" });
  }
}

/** Service-to-service auth for the provisioning route. */
function internalOnly(req, res, next) {
  const provided = req.headers["x-internal-api-key"] ?? "";
  const expected = internalApiKey;
  const a = Buffer.from(String(provided));
  const b = Buffer.from(expected);
  // timingSafeEqual throws on length mismatch, so length-check first.
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    return res.status(401).json({ error: "invalid internal api key" });
  }
  next();
}

// ---- service-to-service ----------------------------------------------------
// Called by Auth's postConfirmation trigger. Replaced by a `user.registered`
// MSK event in a later phase.
app.post("/internal/profiles", internalOnly, async (req, res) => {
  try {
    const result = await provisionProfile(deps, req.body ?? {});
    res.status(result.created ? 201 : 200).json(result.profile);
  } catch (err) {
    fail(res, err);
  }
});

// ---- user-facing -----------------------------------------------------------
// Reading your OWN profile lazily provisions it if absent (getMyProfile);
// reading someone ELSE's still 404s if missing (getProfile).
app.get("/profiles/me", authenticate, async (req, res) => {
  try {
    res.json(await getMyProfile(deps, { userId: req.claims.userId, email: req.claims.email }));
  } catch (err) {
    fail(res, err);
  }
});

app.get("/profiles/:userId", authenticate, async (req, res) => {
  try {
    const self = req.params.userId === req.claims.userId;
    const profile = self
      ? await getMyProfile(deps, { userId: req.claims.userId, email: req.claims.email })
      : await getProfile(deps, { userId: req.params.userId, callerUserId: req.claims.userId });
    res.json(profile);
  } catch (err) {
    fail(res, err);
  }
});

app.patch("/profiles/:userId", authenticate, async (req, res) => {
  try {
    res.json(
      await updateProfile(deps, {
        userId: req.params.userId,
        callerUserId: req.claims.userId,
        fields: req.body ?? {},
      })
    );
  } catch (err) {
    fail(res, err);
  }
});

app.delete("/profiles/:userId", authenticate, async (req, res) => {
  try {
    res.json(
      await deleteProfile(deps, {
        userId: req.params.userId,
        callerUserId: req.claims.userId,
      })
    );
  } catch (err) {
    fail(res, err);
  }
});

app.get("/health", (_req, res) => res.status(200).json({ status: "ok" }));

const port = process.env.PORT ?? 3000;
app.listen(port, () => {
  // eslint-disable-next-line no-console
  console.log(`profile-service (httpServer adapter) listening on :${port}`);
});
