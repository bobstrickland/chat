import { hydrateSecrets } from "./clients/secretsLoader.js";

/**
 * Which secrets THIS service needs, and where each value lands in the
 * environment. One declaration, used by every adapter.
 *
 * `shared/profile-internal-api-key` is the SAME secret the Profile service
 * reads — the two ends of one shared credential, so a single stored value can't
 * drift out of sync between caller and verifier. It lives outside either
 * service's own prefix, which means `terraform/modules/iam` needs an explicit
 * grant to exactly these two roles (see `shared_secrets` there); the blanket
 * `secret:{service}/*` grant deliberately does NOT cover it.
 *
 * Not `required`: Auth→Profile provisioning is best-effort by design (a throwing
 * postConfirmation would fail the user's Cognito confirmation), and lazy
 * provisioning covers the gap. Refusing to start over it would be a worse
 * outcome than the degradation it guards.
 */
export const SECRET_SPECS = [
  {
    secretId:
      process.env.PROFILE_INTERNAL_KEY_SECRET_ID ?? "shared/profile-internal-api-key",
    map: { apiKey: "PROFILE_INTERNAL_API_KEY" },
  },
];

/** Populate the environment from Secrets Manager (no-op unless SECRETS_PROVIDER=awssm). */
export function loadSecrets() {
  return hydrateSecrets(SECRET_SPECS);
}
