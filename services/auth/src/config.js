import { createCognitoClient } from "./clients/cognitoClient.js";
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
    return new LocalAuthProvider({
      signingSecret: process.env.LOCAL_JWT_SIGNING_SECRET ?? "dev-only-not-for-prod",
    });
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
