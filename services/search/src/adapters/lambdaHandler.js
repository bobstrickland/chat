import { getDependencies } from "../config.js";
import { search } from "../core/search.js";
import { indexMessage } from "../core/indexMessage.js";
import { indexProfile } from "../core/indexProfile.js";

// Per CLAUDE.md: no reliance on warm context for correctness — lazy init is a
// warm-start bonus only.
let deps;

/**
 * AWS-side adapters, same cores as httpServer.js:
 *   - `handler`        : API Gateway HTTP — GET /search
 *   - `messageHandler` : MSK-triggered consumer of message.sent (index messages)
 *   - `indexHandler`   : MSK-triggered consumer of search.index (index profiles)
 * Not exercised locally (httpServer.js + the kafkajs consumers are).
 */

export const handler = async (event) => {
  deps ??= getDependencies();
  const method = event.requestContext?.http?.method;
  const path = event.requestContext?.http?.path ?? "";

  if (path === "/health") return reply(200, { status: "ok" });

  if (method === "GET" && path === "/search") {
    const token = bearer(event);
    try {
      await deps.verifyToken(token);
    } catch {
      return reply(401, { error: "invalid token" });
    }
    const params = event.queryStringParameters ?? {};
    const results = await search(deps, {
      q: params.q ?? "",
      type: params.type ?? "all",
      bearerToken: token,
    });
    return reply(200, results);
  }
  return reply(404, { error: "not found" });
};

/** MSK event source for message.sent → index each record. */
export const messageHandler = (event) => consumeRecords(event, (e) => indexMessage(deps, e));

/** MSK event source for search.index → index each record. */
export const indexHandler = (event) => consumeRecords(event, (e) => indexProfile(deps, e));

async function consumeRecords(event, handleOne) {
  deps ??= getDependencies();
  for (const recs of Object.values(event.records ?? {})) {
    for (const rec of recs) {
      try {
        const value = Buffer.from(rec.value, "base64").toString("utf8");
        await handleOne(JSON.parse(value));
      } catch (err) {
        // eslint-disable-next-line no-console
        console.error(`[search] worker record failed: ${err.message}`);
      }
    }
  }
  return { ok: true };
}

function bearer(event) {
  const h = event.headers?.authorization ?? "";
  return h.startsWith("Bearer ") ? h.slice(7) : "";
}

function reply(statusCode, body) {
  return { statusCode, headers: { "content-type": "application/json" }, body: JSON.stringify(body) };
}
