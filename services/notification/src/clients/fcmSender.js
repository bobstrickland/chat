import { createSign } from "node:crypto";

/**
 * FCM (Firebase Cloud Messaging) sender for the Android client — the mobile
 * counterpart to webPushSender.
 *
 * Deliberately dependency-free. The obvious choice is `firebase-admin`, but that
 * pulls ~65 MB of transitive @google-cloud packages into a Lambda deploy package
 * for what FCM HTTP v1 actually needs: an OAuth2 JWT-bearer token exchange and
 * one JSON POST. Contrast with `web-push`, which earns its dependency by
 * implementing RFC-8291 payload encryption (ECDH + HKDF + AES-GCM) — real
 * cryptography we should not hand-roll. Signing a service-account JWT with
 * node:crypto is ~15 lines, so here the library buys nothing.
 *
 * A token FCM reports as unregistered (the app was uninstalled, or data cleared)
 * surfaces as `{ gone: true }`, exactly like a dead web subscription, so the
 * caller prunes it. Same contract as webPushSender: `send()` never throws.
 */

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const TOKEN_URI = "https://oauth2.googleapis.com/token";

/**
 * @param {object} cfg
 * @param {boolean} cfg.enabled     — FCM_ENABLED and credentials both present
 * @param {string}  cfg.projectId   — Firebase project id (from the service account)
 * @param {string}  cfg.clientEmail — service-account email
 * @param {string}  cfg.privateKey  — service-account PEM private key
 * @param {string}  [cfg.channelId] — Android notification channel to post on
 * @param {Function} [cfg.fetchImpl] — injectable for tests
 */
export function createFcmSender(cfg = {}) {
  const { enabled, projectId, clientEmail, privateKey } = cfg;
  const channelId = cfg.channelId ?? "messages";
  const doFetch = cfg.fetchImpl ?? fetch;

  // Not configured is a normal local-dev state (no Firebase project), not an
  // error: the core skips android devices and web push keeps working.
  if (!enabled || !projectId || !clientEmail || !privateKey) {
    return {
      enabled: false,
      async send() {
        return { ok: false, unconfigured: true };
      },
    };
  }

  // Warm-start bonus only — never relied on for correctness (CLAUDE.md). A cold
  // invocation just mints a fresh access token.
  let cached = null;

  async function accessToken() {
    const now = Math.floor(Date.now() / 1000);
    if (cached && cached.expiresAt - 60 > now) {
      return cached.token;
    }
    const assertion = signJwt({ clientEmail, privateKey, now });
    const res = await doFetch(TOKEN_URI, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }).toString(),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok || !body.access_token) {
      throw new Error(
        `FCM token exchange failed (${res.status}): ${body.error_description ?? body.error ?? "unknown"}`
      );
    }
    cached = { token: body.access_token, expiresAt: now + (body.expires_in ?? 3600) };
    return cached.token;
  }

  return {
    enabled: true,

    /**
     * @param {{ token: string }} subscription — the device's FCM registration token
     * @param {{ title: string, body: string, data: object }} payload
     */
    async send(subscription, payload) {
      const token = subscription?.token;
      if (!token) {
        return { ok: false, error: "android subscription has no token" };
      }
      try {
        const bearer = await accessToken();
        const res = await doFetch(
          `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`,
          {
            method: "POST",
            headers: {
              authorization: `Bearer ${bearer}`,
              "content-type": "application/json",
            },
            body: JSON.stringify(buildMessage({ token, payload, channelId })),
          }
        );
        if (res.ok) {
          return { ok: true };
        }
        const body = await res.json().catch(() => ({}));
        const status = body?.error?.status ?? "";
        const detail = body?.error?.message ?? `status ${res.status}`;
        if (isDeadToken(res.status, status)) {
          return { ok: false, gone: true };
        }
        return { ok: false, error: detail, statusCode: res.status };
      } catch (err) {
        // Network blip / token-exchange failure — transient, not a dead token.
        return { ok: false, error: err.message };
      }
    },
  };
}

/**
 * Both a `notification` and a `data` block, on purpose:
 *   - app killed or backgrounded → the Play-services SDK draws the tray
 *     notification itself (our own service never runs), and `data` rides along
 *     as intent extras so the tap can still open the right conversation;
 *   - app in the foreground → onMessageReceived fires and the client posts its
 *     own notification from `data`.
 * A data-only message would be silently dropped in the killed case, which is
 * exactly the case offline push exists for.
 *
 * FCM requires every `data` value to be a string.
 */
function buildMessage({ token, payload, channelId }) {
  const data = {};
  for (const [k, v] of Object.entries(payload.data ?? {})) {
    if (v !== undefined && v !== null) data[k] = String(v);
  }
  return {
    message: {
      token,
      notification: { title: payload.title, body: payload.body },
      data,
      android: {
        priority: "high", // a chat message is user-visible; don't let Doze delay it
        notification: { channel_id: channelId },
      },
    },
  };
}

/**
 * FCM's ways of saying "stop sending to this token":
 *   404 NOT_FOUND / UNREGISTERED — app uninstalled or its data was cleared
 *   403 SENDER_ID_MISMATCH       — token belongs to a different Firebase project
 *   400 INVALID_ARGUMENT         — malformed token (it will never become valid)
 */
function isDeadToken(httpStatus, errorStatus) {
  if (httpStatus === 404 || errorStatus === "NOT_FOUND" || errorStatus === "UNREGISTERED") return true;
  if (httpStatus === 403 && errorStatus === "PERMISSION_DENIED") return true;
  if (errorStatus === "SENDER_ID_MISMATCH") return true;
  if (httpStatus === 400 && errorStatus === "INVALID_ARGUMENT") return true;
  return false;
}

/** Service-account JWT for the OAuth2 jwt-bearer grant (RS256). */
function signJwt({ clientEmail, privateKey, now }) {
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: clientEmail,
    scope: SCOPE,
    aud: TOKEN_URI,
    iat: now,
    exp: now + 3600,
  };
  const unsigned = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const signature = createSign("RSA-SHA256").update(unsigned).sign(privateKey);
  return `${unsigned}.${signature.toString("base64url")}`;
}

function b64url(s) {
  return Buffer.from(s, "utf8").toString("base64url");
}
