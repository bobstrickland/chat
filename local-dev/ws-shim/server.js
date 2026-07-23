/**
 * ws-shim — local stand-in for API Gateway WebSocket.
 *
 * Approximates three things and nothing more:
 *   1. $connect / $disconnect / default route invocations, forwarded as
 *      API-Gateway-shaped events to a backing HTTP service.
 *   2. The @connections management API (postToConnection / getConnection /
 *      deleteConnection), including the 410 Gone behaviour on a stale id.
 *   3. Server-assigned connectionIds.
 *
 * It is NOT a faithful reproduction — see README.md for the gaps. Anything
 * WebSocket-dependent must be revalidated against real API Gateway before
 * it counts as done (CLAUDE.md, "Fidelity gaps").
 */

import http from "node:http";
import { randomUUID } from "node:crypto";
import { WebSocketServer } from "ws";

const PORT = Number(process.env.PORT ?? 8090);

// Where route events get forwarded. Unset is fine and expected until the
// Presence & Connection service exists (Phase 3) — connections still work,
// routes are just logged and dropped.
const ROUTE_TARGET = process.env.WS_SHIM_ROUTE_TARGET ?? "";
const MANAGE_PATH = process.env.WS_SHIM_MANAGE_CONNECTIONS_PATH ?? "/@connections";
const STAGE = process.env.WS_SHIM_STAGE ?? "local";

/** connectionId -> { socket, connectedAt } */
const connections = new Map();

const log = (...args) => console.log(`[ws-shim]`, ...args);

/** Build an event shaped like what a Lambda WebSocket integration receives. */
function buildEvent(routeKey, connectionId, body, extra = {}) {
  const eventType =
    routeKey === "$connect" ? "CONNECT" : routeKey === "$disconnect" ? "DISCONNECT" : "MESSAGE";
  return {
    requestContext: {
      routeKey,
      eventType,
      connectionId,
      domainName: `ws-shim:${PORT}`,
      stage: STAGE,
      requestId: randomUUID(),
      requestTimeEpoch: Date.now(),
    },
    // On $connect API Gateway includes the handshake's query string and headers
    // — the only channel a browser has to pass a token (it can't set custom WS
    // headers), so auth rides in queryStringParameters.token. These are absent
    // on $disconnect/$default, matching real API Gateway.
    ...(extra.queryStringParameters ? { queryStringParameters: extra.queryStringParameters } : {}),
    ...(extra.headers ? { headers: extra.headers } : {}),
    body: body ?? null,
    isBase64Encoded: false,
  };
}

/** Flatten Node's raw header array into the {name: value} shape API Gateway uses. */
function parseQuery(url) {
  const out = {};
  const q = new URL(url, `http://localhost:${PORT}`).searchParams;
  for (const [k, v] of q) out[k] = v;
  return Object.keys(out).length ? out : null;
}

async function forwardRoute(routeKey, connectionId, body, extra = {}) {
  if (!ROUTE_TARGET) {
    log(`route ${routeKey} (${connectionId}) — no WS_SHIM_ROUTE_TARGET set, dropping`);
    return { statusCode: 200 };
  }
  const url = `${ROUTE_TARGET.replace(/\/$/, "")}/ws`;
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(buildEvent(routeKey, connectionId, body, extra)),
    });
    if (!res.ok) log(`route ${routeKey} -> ${url} returned ${res.status}`);
    return { statusCode: res.status };
  } catch (err) {
    log(`route ${routeKey} -> ${url} failed: ${err.message}`);
    // Real API Gateway rejects the handshake if $connect fails. Mirror that.
    return { statusCode: 502 };
  }
}

// ---------------------------------------------------------------------------
// Management API (mirrors ApiGatewayManagementApi)
// ---------------------------------------------------------------------------

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

const httpServer = http.createServer(async (req, res) => {
  const { pathname } = new URL(req.url, `http://localhost:${PORT}`);

  if (pathname === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    return res.end(JSON.stringify({ ok: true, connections: connections.size }));
  }

  // Debug aid — no API Gateway equivalent.
  if (pathname === MANAGE_PATH && req.method === "GET") {
    res.writeHead(200, { "content-type": "application/json" });
    return res.end(
      JSON.stringify(
        [...connections.entries()].map(([id, c]) => ({ connectionId: id, connectedAt: c.connectedAt })),
      ),
    );
  }

  if (pathname.startsWith(`${MANAGE_PATH}/`)) {
    const connectionId = decodeURIComponent(pathname.slice(MANAGE_PATH.length + 1));
    const entry = connections.get(connectionId);

    // API Gateway returns 410 GoneException for an unknown/stale connection.
    if (!entry) {
      res.writeHead(410, { "content-type": "application/json" });
      return res.end(JSON.stringify({ message: "GoneException", connectionId }));
    }

    if (req.method === "POST") {
      const body = await readBody(req);
      // Send as a text frame. Passing the raw Buffer makes `ws` emit a binary
      // frame, which surfaces client-side as a Blob — API Gateway delivers
      // these payloads as text.
      entry.socket.send(body.toString("utf8"));
      res.writeHead(200);
      return res.end();
    }
    if (req.method === "GET") {
      res.writeHead(200, { "content-type": "application/json" });
      return res.end(JSON.stringify({ connectionId, connectedAt: entry.connectedAt }));
    }
    if (req.method === "DELETE") {
      entry.socket.close(1000, "deleted via management API");
      res.writeHead(204);
      return res.end();
    }
  }

  res.writeHead(404, { "content-type": "application/json" });
  res.end(JSON.stringify({ message: "NotFound" }));
});

// ---------------------------------------------------------------------------
// WebSocket side
// ---------------------------------------------------------------------------

const wss = new WebSocketServer({ noServer: true });

httpServer.on("upgrade", async (req, socket, head) => {
  const connectionId = randomUUID();

  // $connect runs BEFORE the handshake completes, and a non-2xx rejects it.
  // Pass the handshake query string + headers so the route target can
  // authenticate the connection and learn which user it belongs to.
  const result = await forwardRoute("$connect", connectionId, null, {
    queryStringParameters: parseQuery(req.url),
    headers: req.headers,
  });
  if (result.statusCode >= 300) {
    log(`$connect rejected for ${connectionId} (${result.statusCode})`);
    socket.write("HTTP/1.1 403 Forbidden\r\n\r\n");
    return socket.destroy();
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    connections.set(connectionId, { socket: ws, connectedAt: new Date().toISOString() });
    log(`connected ${connectionId} (${connections.size} open)`);

    // Not an API Gateway behaviour — clients need to learn their own id
    // somehow, and locally there's no signed handshake to carry it.
    ws.send(JSON.stringify({ type: "__shim.connected", connectionId }));

    ws.on("message", (data) => forwardRoute("$default", connectionId, data.toString()));

    ws.on("close", () => {
      connections.delete(connectionId);
      log(`disconnected ${connectionId} (${connections.size} open)`);
      forwardRoute("$disconnect", connectionId, null);
    });

    ws.on("error", (err) => log(`socket error ${connectionId}: ${err.message}`));
  });
});

httpServer.listen(PORT, () => {
  log(`listening on ${PORT}; management API at ${MANAGE_PATH}`);
  log(ROUTE_TARGET ? `forwarding routes to ${ROUTE_TARGET}/ws` : `WS_SHIM_ROUTE_TARGET unset — routes dropped`);
});
