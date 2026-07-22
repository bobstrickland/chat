import { getIdentityProvider } from "../config.js";
import { register } from "../core/register.js";
import { login } from "../core/login.js";
import { refreshToken } from "../core/refreshToken.js";
import { enrollMfa } from "../core/enrollMfa.js";
import { verifyMfa } from "../core/verifyMfa.js";
import { federatedLogin } from "../core/federatedLogin.js";
import { verifyToken } from "../core/verifyToken.js";

// Per CLAUDE.md: no reliance on Lambda execution-context reuse for
// correctness. This module-level instance is a warm-start perf bonus only —
// every call path below still works correctly on a cold start.
let identityProvider;

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
  identityProvider ??= getIdentityProvider();

  if (event.requestContext?.http?.path === "/health") {
    return { statusCode: 200, body: JSON.stringify({ status: "ok" }) };
  }

  const routeKey = `${event.requestContext?.http?.method} ${event.requestContext?.http?.path}`;
  const coreFn = ROUTES[routeKey];

  if (!coreFn) {
    return { statusCode: 404, body: JSON.stringify({ error: "not found" }) };
  }

  try {
    const body = event.body ? JSON.parse(event.body) : {};
    const result = await coreFn(identityProvider, body);
    return { statusCode: 200, body: JSON.stringify(result) };
  } catch (err) {
    return { statusCode: 400, body: JSON.stringify({ error: err.message }) };
  }
};
