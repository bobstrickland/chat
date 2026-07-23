/**
 * Thin client for the Profile Service's internal provisioning route.
 *
 * Cross-service data goes via API call or MSK event — never a direct table
 * read (CLAUDE.md). Auth must not touch the `profiles` table itself.
 *
 * This is the stopgap form. Once MSK is in play (Phase 3+), Auth publishes a
 * `user.registered` event and Profile consumes it, at which point this client
 * and the shared secret both go away.
 */
export function createProfileServiceClient({ baseUrl, internalApiKey, timeoutMs = 3000 }) {
  return {
    async provisionProfile({ userId, email }) {
      if (!baseUrl || !internalApiKey) {
        throw new Error(
          "PROFILE_SERVICE_URL and PROFILE_INTERNAL_API_KEY are required to provision profiles"
        );
      }

      const res = await fetch(`${baseUrl.replace(/\/$/, "")}/internal/profiles`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-internal-api-key": internalApiKey,
        },
        body: JSON.stringify({ userId, email }),
        signal: AbortSignal.timeout(timeoutMs),
      });

      if (!res.ok) {
        throw new Error(`profile provisioning failed: ${res.status} ${await res.text()}`);
      }

      return res.json();
    },
  };
}
