import { getDependencies } from "../config.js";
import { register } from "../core/register.js";
import { login } from "../core/login.js";
import { refreshToken } from "../core/refreshToken.js";
import { enrollMfa } from "../core/enrollMfa.js";
import { verifyMfa } from "../core/verifyMfa.js";
import { federatedLogin } from "../core/federatedLogin.js";
import { verifyToken } from "../core/verifyToken.js";
import { deleteAccount } from "../core/deleteAccount.js";

// Per CLAUDE.md: no reliance on Lambda execution-context reuse for
// correctness. This module-level instance is a warm-start perf bonus only —
// every call path below still works correctly on a cold start.
let deps;

const ROUTES = {
  "POST /auth/register": register,
  "POST /auth/login": login,
  "POST /auth/refresh": refreshToken,
  "POST /auth/mfa/enroll": enrollMfa,
  "POST /auth/mfa/verify": verifyMfa,
  "POST /auth/federated": federatedLogin,
  "POST /auth/verify-token": verifyToken,
};

/**
 * API Gateway (HTTP API / Lambda proxy integration) handler.
 * Unwraps the event, calls the same core/ functions httpServer.js uses,
 * wraps the result — no business logic lives here.
 */
export const handler = async (event) => {
  deps ??= getDependencies();

  if (event.requestContext?.http?.path === "/health") {
    return { statusCode: 200, body: JSON.stringify({ status: "ok" }) };
  }

  const routeKey = `${event.requestContext?.http?.method} ${event.requestContext?.http?.path}`;

  // Account deletion (Phase 12.5) is token-driven, not body-driven, so it can't
  // go through the generic (deps, body) ROUTES map — handle it explicitly.
  if (routeKey === "DELETE /auth/account") {
    const header = event.headers?.authorization ?? event.headers?.Authorization ?? "";
    const accessToken = header.startsWith("Bearer ") ? header.slice(7) : null;
    if (!accessToken) {
      return { statusCode: 401, body: JSON.stringify({ error: "missing bearer token" }) };
    }
    try {
      const claims = await deps.identityProvider.verifyToken({ token: accessToken });
      const result = await deleteAccount(deps, { userId: claims.userId, accessToken });
      return { statusCode: 200, body: JSON.stringify(result) };
    } catch (err) {
      return { statusCode: 400, body: JSON.stringify({ error: err.message }) };
    }
  }

  const coreFn = ROUTES[routeKey];

  if (!coreFn) {
    return { statusCode: 404, body: JSON.stringify({ error: "not found" }) };
  }

  try {
    const body = event.body ? JSON.parse(event.body) : {};
    const result = await coreFn(deps, body);
    return { statusCode: 200, body: JSON.stringify(result) };
  } catch (err) {
    return { statusCode: 400, body: JSON.stringify({ error: err.message }) };
  }
};
