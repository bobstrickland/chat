import { createCognitoClient } from "./clients/cognitoClient.js";
import { createDynamoClient } from "./clients/dynamoClient.js";
import { createUserRepository } from "./clients/userRepository.js";
import { CognitoProvider } from "./providers/CognitoProvider.js";
import { LocalAuthProvider } from "./providers/LocalAuthProvider.js";

/**
 * Single place that decides which IdentityProvider implementation is
 * active. core/ never does this selection itself — it just calls
 * whatever getIdentityProvider() returns.
 */
export function getIdentityProvider() {
  const providerType = process.env.AUTH_PROVIDER ?? "cognito";

  if (providerType === "local") {
    // No fallback on purpose. A hardcoded default here would be a signing key
    // published in a public repo — anyone could forge tokens the moment this
    // provider ran outside local dev. Fail loudly instead.
    const signingSecret = process.env.LOCAL_JWT_SIGNING_SECRET;
    if (!signingSecret) {
      throw new Error(
        "LOCAL_JWT_SIGNING_SECRET is required when AUTH_PROVIDER=local — set it in .env"
      );
    }

    return new LocalAuthProvider({ signingSecret });
  }

  if (providerType === "cognito") {
    const config = {
      region: process.env.COGNITO_REGION ?? process.env.AWS_REGION,
      userPoolId: process.env.COGNITO_USER_POOL_ID,
      clientId: process.env.COGNITO_CLIENT_ID_WEB, // adapters may override per client type
      jwksUrl: process.env.COGNITO_JWKS_URL,
      hostedUiDomain: process.env.COGNITO_HOSTED_UI_DOMAIN,
    };

    for (const [key, value] of Object.entries(config)) {
      if (!value) {
        // eslint-disable-next-line no-console
        console.warn(`[config] missing COGNITO env var for "${key}" — check .env`);
      }
    }

    return new CognitoProvider(createCognitoClient(config), config);
  }

  throw new Error(`unknown AUTH_PROVIDER: ${providerType}`);
}

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
    identityProvider: getIdentityProvider(),
    userRepository: createUserRepository(docClient, process.env.USERS_TABLE),
  };
}
