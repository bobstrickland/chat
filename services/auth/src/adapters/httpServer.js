import express from "express";
import { getDependencies } from "../config.js";
import { loadSecrets } from "../secrets.js";
import { register } from "../core/register.js";
import { login } from "../core/login.js";
import { refreshToken } from "../core/refreshToken.js";
import { enrollMfa } from "../core/enrollMfa.js";
import { verifyMfa } from "../core/verifyMfa.js";
import { federatedLogin } from "../core/federatedLogin.js";
import { verifyToken } from "../core/verifyToken.js";
import { deleteAccount } from "../core/deleteAccount.js";

const app = express();
app.use(express.json());

// Do not cache these across requests as a correctness dependency (per
// CLAUDE.md — code as if every invocation is cold), but a module-level
// bundle is fine here since httpServer.js is warm-process by design
// (Fargate target). The providers and the repository hold no per-request
// state.
// Secrets BEFORE dependencies — config reads them straight from the
// environment. No-op unless SECRETS_PROVIDER=awssm. Top-level await (ESM).
const secrets = await loadSecrets();
if (secrets.provider === "awssm") {
  // eslint-disable-next-line no-console
  console.log(`[secrets] loaded from Secrets Manager: ${secrets.loaded.join(", ") || "none"}`);
}

const deps = getDependencies();

function handle(fn) {
  return async (req, res) => {
    try {
      const result = await fn(deps, req.body ?? {});
      res.status(200).json(result);
    } catch (err) {
      res.status(400).json({ error: err.message });
    }
  };
}

app.post("/auth/register", handle(register));
app.post("/auth/login", handle(login));
app.post("/auth/refresh", handle(refreshToken));
app.post("/auth/mfa/enroll", handle(enrollMfa));
app.post("/auth/mfa/verify", handle(verifyMfa));
app.post("/auth/federated", handle(federatedLogin));
app.post("/auth/verify-token", handle(verifyToken));

// Full account deletion (Phase 12.5). Needs the raw access token (to delete the
// Cognito user + forward to Profile), which handle() doesn't pass — so it reads
// the bearer directly. The token IS the authorization: you can only ever delete
// your own account. Claims are verified so we have the userId to hand off.
app.delete("/auth/account", async (req, res) => {
  const header = req.headers.authorization ?? "";
  const accessToken = header.startsWith("Bearer ") ? header.slice(7) : null;
  if (!accessToken) {
    return res.status(401).json({ error: "missing bearer token" });
  }
  try {
    const claims = await deps.identityProvider.verifyToken({ token: accessToken });
    const result = await deleteAccount(deps, { userId: claims.userId, accessToken });
    res.status(200).json(result);
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/health", (_req, res) => res.status(200).json({ status: "ok" }));

const port = process.env.PORT ?? 3000;
app.listen(port, () => {
  // eslint-disable-next-line no-console
  console.log(`auth-service (httpServer adapter) listening on :${port}`);
});
