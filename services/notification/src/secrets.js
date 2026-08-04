import { hydrateSecrets } from "./clients/secretsLoader.js";

/**
 * Which secrets THIS service needs, and where each value lands in the
 * environment. One declaration, used by both adapters.
 *
 * Naming: `{service}/{name}`, because the IAM policy in
 * `terraform/modules/iam` scopes each service role to
 * `secret:{service}/*` — so a secret outside its own prefix is unreadable by
 * design. Values are populated out-of-band (CLI/console), never by Terraform:
 * a `secret_version` resource would write the plaintext into state.
 *
 * Neither is `required`. Both features degrade rather than break: without VAPID
 * the service still consumes triggers and serves device registration (web push
 * fails loudly per-send), and FCM is already optional. Refusing to start would
 * turn one missing secret into a dead service.
 */
export const SECRET_SPECS = [
  {
    secretId: process.env.VAPID_SECRET_ID ?? "notification/vapid",
    map: {
      publicKey: "VAPID_PUBLIC_KEY",
      privateKey: "VAPID_PRIVATE_KEY",
      subject: "VAPID_SUBJECT",
    },
  },
  {
    secretId: process.env.FCM_SECRET_ID ?? "notification/fcm-service-account",
    // `whole`, not `map`: this is a Google service-account document that must be
    // handed to the FCM sender verbatim. Destructuring it would silently mangle
    // the PEM private key's newlines.
    whole: "FCM_SERVICE_ACCOUNT_JSON",
  },
];

/** Populate the environment from Secrets Manager (no-op unless SECRETS_PROVIDER=awssm). */
export function loadSecrets() {
  return hydrateSecrets(SECRET_SPECS);
}
