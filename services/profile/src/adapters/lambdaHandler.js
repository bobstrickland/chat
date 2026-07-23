import crypto from "node:crypto";
import { getDependencies, getInternalApiKey } from "../config.js";
import { provisionProfile } from "../core/provisionProfile.js";
import { getProfile } from "../core/getProfile.js";
import { getMyProfile } from "../core/getMyProfile.js";
import { updateProfile } from "../core/updateProfile.js";
import { deleteProfile } from "../core/deleteProfile.js";

// Per CLAUDE.md: no reliance on Lambda execution-context reuse for
// correctness. These module-level values are a warm-start perf bonus only —
// every path below still works on a cold start.
let deps;
let internalApiKey;

const STATUS = { NOT_FOUND: 404, FORBIDDEN: 403 };

const reply = (statusCode, body) => ({ statusCode, body: JSON.stringify(body) });

function fail(err) {
  if (err.message === "unauthenticated") return reply(401, { error: err.message });
  return reply(STATUS[err.code] ?? 400, { error: err.message });
}

function headerValue(headers, name) {
  // API Gateway header casing is not guaranteed.
  const key = Object.keys(headers ?? {}).find((k) => k.toLowerCase() === name);
  return key ? headers[key] : undefined;
}

async function authenticate(event) {
  const header = headerValue(event.headers, "authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : null;
  if (!token) throw Object.assign(new Error("missing bearer token"), { unauth: true });
  try {
    return await deps.verifyToken(token);
  } catch {
    throw Object.assign(new Error("invalid token"), { unauth: true });
  }
}

function assertInternal(event) {
  const provided = headerValue(event.headers, "x-internal-api-key") ?? "";
  const a = Buffer.from(String(provided));
  const b = Buffer.from(internalApiKey);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    throw Object.assign(new Error("invalid internal api key"), { unauth: true });
  }
}

/**
 * API Gateway (HTTP API / Lambda proxy integration) handler. Unwraps the
 * event, calls the same core/ functions httpServer.js uses, wraps the
 * result — no business logic lives here.
 */
export const handler = async (event) => {
  deps ??= getDependencies();
  internalApiKey ??= getInternalApiKey();

  const method = event.requestContext?.http?.method;
  const path = event.requestContext?.http?.path ?? "";

  if (path === "/health") return reply(200, { status: "ok" });

  try {
    const body = event.body ? JSON.parse(event.body) : {};

    if (method === "POST" && path === "/internal/profiles") {
      assertInternal(event);
      const result = await provisionProfile(deps, body);
      return reply(result.created ? 201 : 200, result.profile);
    }

    const match = path.match(/^\/profiles\/([^/]+)$/);
    if (!match) return reply(404, { error: "not found" });

    const claims = await authenticate(event);
    const target = match[1] === "me" ? claims.userId : decodeURIComponent(match[1]);

    if (method === "GET") {
      // Own profile lazily provisions; others' 404 if missing.
      const self = target === claims.userId;
      const profile = self
        ? await getMyProfile(deps, { userId: claims.userId, email: claims.email })
        : await getProfile(deps, { userId: target, callerUserId: claims.userId });
      return reply(200, profile);
    }
    if (method === "PATCH") {
      return reply(
        200,
        await updateProfile(deps, { userId: target, callerUserId: claims.userId, fields: body })
      );
    }
    if (method === "DELETE") {
      return reply(200, await deleteProfile(deps, { userId: target, callerUserId: claims.userId }));
    }

    return reply(404, { error: "not found" });
  } catch (err) {
    if (err.unauth) return reply(401, { error: err.message });
    return fail(err);
  }
};
