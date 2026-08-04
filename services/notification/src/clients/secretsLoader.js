import { SecretsManagerClient, GetSecretValueCommand } from "@aws-sdk/client-secrets-manager";

/**
 * Loads secrets from AWS Secrets Manager into `process.env`, so every existing
 * (synchronous) config path keeps working untouched.
 *
 * Two modes, the same shape as the other switches (`KAFKA_AUTH`, `WS_PROVIDER`,
 * `OPENSEARCH_AUTH`):
 *
 *   env    (default, incl. unset/blank) → no-op; values come from .env as they
 *                                         always have
 *   awssm  → fetch each spec and populate the mapped env vars before anything
 *            reads them
 *
 * WHY hydrate `process.env` instead of returning values: config.js and every
 * client are synchronous, and a secret fetch is not. Hydrating once at startup
 * keeps the async boundary in exactly one place — the adapter — rather than
 * turning `getDependencies()` and its callers async for four values. It also
 * means the local path is literally unchanged code.
 *
 * Fetched values OVERRIDE anything already in the environment. A deployment that
 * says "get secrets from Secrets Manager" must not silently keep using a stale
 * value baked into a task definition.
 *
 * DELIBERATELY NOT CACHED ACROSS INVOCATIONS beyond the process's own env: this
 * runs once per cold start, and CLAUDE.md forbids depending on warm-start reuse
 * for correctness. The flip side is rotation — a rotated secret is picked up on
 * the next cold start, not mid-life. For a long-lived Fargate task that means a
 * restart. Fine for these four (none rotate automatically today); revisit if a
 * rotation schedule is ever attached.
 */

/**
 * @param {Array<{secretId: string, map: Record<string,string>, whole?: string, required?: boolean}>} specs
 *   secretId — Secrets Manager name or ARN
 *   map      — JSON key → env var name
 *   whole    — env var that receives the ENTIRE secret string (for opaque blobs
 *              like a service-account JSON, which must not be destructured)
 *   required — throw if missing (default false: log and fall through to .env)
 */
export async function hydrateSecrets(specs) {
  const mode = (process.env.SECRETS_PROVIDER ?? "").trim().toLowerCase();
  if (mode === "" || mode === "env") {
    return { provider: "env", loaded: [] };
  }
  if (mode !== "awssm") {
    throw new Error(`SECRETS_PROVIDER must be 'env' or 'awssm', got: '${process.env.SECRETS_PROVIDER}'`);
  }

  const client = new SecretsManagerClient({
    region: process.env.AWS_REGION,
    // LocalStack in dev; unset in AWS so the SDK uses the real endpoint.
    ...(process.env.SECRETS_MANAGER_ENDPOINT
      ? { endpoint: process.env.SECRETS_MANAGER_ENDPOINT }
      : {}),
  });

  const loaded = [];
  for (const spec of specs) {
    try {
      const res = await client.send(new GetSecretValueCommand({ SecretId: spec.secretId }));
      const raw = res.SecretString;
      if (!raw) {
        throw new Error("secret has no SecretString (binary secrets are not supported)");
      }

      if (spec.whole) {
        process.env[spec.whole] = raw;
        loaded.push(spec.secretId);
        continue;
      }

      const parsed = JSON.parse(raw);
      for (const [jsonKey, envVar] of Object.entries(spec.map ?? {})) {
        if (parsed[jsonKey] === undefined || parsed[jsonKey] === null) {
          // eslint-disable-next-line no-console
          console.warn(`[secrets] ${spec.secretId} has no '${jsonKey}' — leaving ${envVar} as-is`);
          continue;
        }
        process.env[envVar] = String(parsed[jsonKey]);
      }
      loaded.push(spec.secretId);
    } catch (err) {
      if (spec.required) {
        // A required secret that can't be read is fatal: starting up and failing
        // later, per request, is strictly worse than not starting.
        throw new Error(`[secrets] required secret ${spec.secretId} unavailable: ${err.message}`);
      }
      // eslint-disable-next-line no-console
      console.warn(
        `[secrets] ${spec.secretId} unavailable (${err.message}) — falling back to the environment`
      );
    }
  }
  return { provider: "awssm", loaded };
}
