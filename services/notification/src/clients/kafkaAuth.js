import { generateAuthToken } from "aws-msk-iam-sasl-signer-js";

/**
 * Kafka transport security for kafkajs — the Node counterpart to the Java
 * services' `KafkaSecurity`, driven by the SAME `KAFKA_AUTH` env var so one
 * setting covers the whole stack.
 *
 *   plaintext (default, incl. unset) → returns {}; local Redpanda listens
 *                                      plaintext with no auth
 *   iam                              → TLS + SASL/OAUTHBEARER against MSK
 *
 * Defaulting to plaintext is deliberate: a wrong default should mean "doesn't
 * work in AWS until one env var is set", not "doesn't work on any dev machine".
 *
 * Why OAUTHBEARER and not the Java mechanism name: MSK's IAM auth is SASL
 * OAUTHBEARER on the wire, carrying a presigned SigV4 token. The Java client
 * hides that behind its own `AWS_MSK_IAM` mechanism; kafkajs has no such
 * mechanism, so we generate the token ourselves via AWS's official signer and
 * hand it over as a bearer token. Different-looking config, identical protocol.
 *
 * The provider is called PER CONNECTION and again on re-authentication, which is
 * what makes this work long-term: MSK auth tokens expire (~15 min), so a token
 * fetched once at startup would break a long-lived consumer. Nothing is cached
 * here on purpose — the signer is a local SigV4 computation against credentials
 * from the default provider chain (the task/Lambda role), not a network call.
 */
export function kafkaAuthOptions(mode = process.env.KAFKA_AUTH, region = process.env.AWS_REGION) {
  const normalized = (mode ?? "plaintext").trim().toLowerCase();
  if (normalized !== "iam") {
    return {}; // plaintext — no ssl, no sasl
  }
  const awsRegion = region || "us-east-1";
  return {
    ssl: true,
    sasl: {
      mechanism: "oauthbearer",
      oauthBearerProvider: async () => {
        const { token } = await generateAuthToken({ region: awsRegion });
        return { value: token };
      },
    },
  };
}
