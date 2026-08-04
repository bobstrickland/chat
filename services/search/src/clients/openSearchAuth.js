import { AwsSigv4Signer } from "@opensearch-project/opensearch/aws";

/**
 * Transport auth for the OpenSearch client, the same shape as the Kafka
 * (`KAFKA_AUTH`) and WebSocket (`WS_PROVIDER`) switches: one env var,
 * local-friendly default.
 *
 *   none (default, incl. unset) → plain HTTP, no signing. The local container
 *                                 runs with DISABLE_SECURITY_PLUGIN, so there is
 *                                 nothing to authenticate to.
 *   iam                        → every request SigV4-signed. A real managed
 *                                 domain rejects unsigned requests with 403.
 *
 * `service` distinguishes the two flavours of managed OpenSearch, and getting it
 * wrong produces a signature mismatch rather than a helpful error:
 *   es   — a provisioned OpenSearch/Elasticsearch DOMAIN (what terraform would
 *          create today)
 *   aoss — OpenSearch SERVERLESS collections
 *
 * Credentials come from the default AWS provider chain. The signer discovers
 * `@aws-sdk/credential-provider-node` by dynamic import, which is why that
 * package is an explicit dependency of this service rather than something we let
 * arrive transitively — the failure mode otherwise is a runtime
 * "Unable to find a valid AWS SDK" the first time anything is indexed.
 *
 * Unrecognised values throw. Silently falling back to unsigned would mean every
 * request to a real domain 403s, which surfaces as "search returns nothing"
 * rather than a startup error.
 */
export function openSearchAuthOptions(
  mode = process.env.OPENSEARCH_AUTH,
  region = process.env.AWS_REGION,
  service = process.env.OPENSEARCH_SERVICE,
) {
  // Blank counts as unset, not as an invalid value: `OPENSEARCH_AUTH=` in an env
  // file arrives here as "", and that must mean the local default rather than a
  // startup crash.
  const raw = (mode ?? "").trim();
  const normalized = raw === "" ? "none" : raw.toLowerCase();

  if (normalized === "none") {
    return {};
  }
  if (normalized !== "iam") {
    throw new Error(`OPENSEARCH_AUTH must be 'none' or 'iam', got: '${mode}'`);
  }

  const awsRegion = (region ?? "").trim();
  if (!awsRegion) {
    // The signer's own message for this is just "Region cannot be empty", which
    // doesn't say which variable to set.
    throw new Error("AWS_REGION is required when OPENSEARCH_AUTH=iam");
  }

  const awsService = (service ?? "es").trim().toLowerCase();
  if (awsService !== "es" && awsService !== "aoss") {
    throw new Error(`OPENSEARCH_SERVICE must be 'es' or 'aoss', got: '${service}'`);
  }

  return AwsSigv4Signer({ region: awsRegion, service: awsService });
}
