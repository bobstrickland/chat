import jwt from "jsonwebtoken";
import jwksClient from "jwks-rsa";

/**
 * Provider-agnostic JWT verification. Points at *a* JWKS URL — today that's
 * Cognito's, but nothing here is Cognito-specific. This same module (or a
 * copy of it) is what every other service uses to verify tokens without
 * ever calling Cognito directly — see CLAUDE.md "Cognito isolation" rule.
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
 * @param {string} token
 * @param {string} jwksUrl
 * @returns {Promise<object>} raw decoded claims (provider-native shape —
 *   callers are responsible for normalizing before this leaves the Auth
 *   Service boundary; see CognitoProvider.verifyToken)
 */
export async function verifyCognitoToken(token, jwksUrl) {
  const decoded = jwt.decode(token, { complete: true });
  if (!decoded) throw new Error("invalid token");

  const client = getClient(jwksUrl);
  const publicKey = await getSigningKey(client, decoded.header.kid);

  return jwt.verify(token, publicKey, { algorithms: ["RS256"] });
}
