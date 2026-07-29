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

    /**
     * Delete a user's profile data (profile row + their contacts + search
     * de-index) as part of full account deletion. Calls Profile's own
     * self-authenticated `DELETE /profiles/{userId}` route with the caller's
     * bearer — no internal key, no privileged "delete any profile" surface, and
     * Profile's own self-only check still applies. Must be called while the
     * access token is still valid (i.e. BEFORE the Cognito user is deleted).
     */
    async deleteProfileAsUser({ userId, accessToken }) {
      if (!baseUrl) {
        throw new Error("PROFILE_SERVICE_URL is required to delete profiles");
      }

      const res = await fetch(
        `${baseUrl.replace(/\/$/, "")}/profiles/${encodeURIComponent(userId)}`,
        {
          method: "DELETE",
          headers: { authorization: `Bearer ${accessToken}` },
          signal: AbortSignal.timeout(timeoutMs),
        }
      );

      if (!res.ok) {
        throw new Error(`profile deletion failed: ${res.status} ${await res.text()}`);
      }

      return res.json();
    },
  };
}
