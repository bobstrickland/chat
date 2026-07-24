import jwt from "jsonwebtoken";
import jwksClient from "jwks-rsa";

/**
 * Provider-agnostic JWT verification — the shared JWKS pattern every
 * non-Auth service uses.
 *
 * CLAUDE.md "Cognito isolation": this service must NEVER import the Cognito
 * SDK or call Cognito APIs. It only fetches public signing keys over HTTPS
 * from a JWKS URL and verifies signatures locally. Nothing here is
 * Cognito-specific beyond the URL that happens to be configured.
 *
 * Claims are normalized into the app's own shape before leaving this module,
 * so no provider-specific claim name (`cognito:username`, `token_use`, …)
 * reaches core/.
 */

const clientCache = new Map();

function getClient(jwksUrl) {
  if (!clientCache.has(jwksUrl)) {
    clientCache.set(
      jwksUrl,
      jwksClient({
        jwksUri: jwksUrl,
        cache: true,
        cacheMaxAge: 10 * 60 * 1000, // 10 min
        rateLimit: true,
      })
    );
  }
  return clientCache.get(jwksUrl);
}

function getSigningKey(client, kid) {
  return new Promise((resolve, reject) => {
    client.getSigningKey(kid, (err, key) => {
      if (err) return reject(err);
      resolve(key.getPublicKey());
    });
  });
}

/**
 * @returns {Promise<{ userId: string, email: string|null, tokenUse: string }>}
 *   normalized claims — never the raw provider shape.
 */
export function createTokenVerifier(jwksUrl) {
  if (!jwksUrl) {
    throw new Error("COGNITO_JWKS_URL is not configured");
  }

  return async function verifyToken(token) {
    const decoded = jwt.decode(token, { complete: true });
    if (!decoded) throw new Error("invalid token");

    const publicKey = await getSigningKey(getClient(jwksUrl), decoded.header.kid);
    const claims = jwt.verify(token, publicKey, { algorithms: ["RS256"] });

    return {
      userId: claims.sub,
      email: claims.email ?? null,
      tokenUse: claims.token_use === "id" ? "id" : "access",
    };
  };
}
