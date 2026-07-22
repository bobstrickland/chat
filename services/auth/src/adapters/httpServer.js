import express from "express";
import { getDependencies } from "../config.js";
import { register } from "../core/register.js";
import { login } from "../core/login.js";
import { refreshToken } from "../core/refreshToken.js";
import { enrollMfa } from "../core/enrollMfa.js";
import { verifyMfa } from "../core/verifyMfa.js";
import { federatedLogin } from "../core/federatedLogin.js";
import { verifyToken } from "../core/verifyToken.js";

const app = express();
app.use(express.json());

// Do not cache these across requests as a correctness dependency (per
// CLAUDE.md — code as if every invocation is cold), but a module-level
// bundle is fine here since httpServer.js is warm-process by design
// (Fargate target). The providers and the repository hold no per-request
// state.
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

app.get("/health", (_req, res) => res.status(200).json({ status: "ok" }));

const port = process.env.PORT ?? 3000;
app.listen(port, () => {
  // eslint-disable-next-line no-console
  console.log(`auth-service (httpServer adapter) listening on :${port}`);
});
