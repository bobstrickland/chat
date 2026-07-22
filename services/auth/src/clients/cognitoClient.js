import { CognitoIdentityProviderClient } from "@aws-sdk/client-cognito-identity-provider";

/**
 * Only this file and CognitoProvider.js may import the Cognito SDK.
 * Everything else in the app talks to CognitoProvider through IdentityProvider.
 */
export function createCognitoClient(config) {
  return new CognitoIdentityProviderClient({
    region: config.region,
    // Local dev note: standard Cognito has no local emulator. This client
    // always talks to a real (dev) User Pool per AUTH_PROVIDER=cognito —
    // see local-dev docs in CLAUDE.md.
  });
}
