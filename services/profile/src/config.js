import { createDynamoClient } from "./clients/dynamoClient.js";
import { createProfileRepository } from "./clients/profileRepository.js";
import { createTokenVerifier } from "./clients/jwksVerifier.js";

/**
 * Builds the dependency bundle every core/ function receives. core/ never
 * constructs a client itself — it only uses what's handed to it here, which
 * is what keeps core/ free of AWS SDK imports and testable with plain fakes.
 */
export function getDependencies() {
  const docClient = createDynamoClient({
    region: process.env.AWS_REGION,
    endpoint: process.env.DYNAMODB_ENDPOINT,
  });

  return {
    profileRepository: createProfileRepository(docClient, process.env.PROFILES_TABLE),
    verifyToken: createTokenVerifier(process.env.COGNITO_JWKS_URL),
  };
}

/**
 * Shared secret for the service-to-service provisioning route. No in-code
 * fallback on purpose — a default here would let anyone create profiles.
 * Fails loudly at startup instead.
 *
 * This is a stopgap: once MSK is in play (Phase 3+), Auth publishes
 * `user.registered` and Profile consumes it, removing this endpoint and its
 * shared secret entirely.
 */
export function getInternalApiKey() {
  const key = process.env.PROFILE_INTERNAL_API_KEY;
  if (!key) {
    throw new Error("PROFILE_INTERNAL_API_KEY is required — set it in .env");
  }
  return key;
}
