import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient } from "@aws-sdk/lib-dynamodb";

/**
 * Creates the DynamoDB document client. The endpoint override is the whole
 * local-vs-AWS difference — DYNAMODB_ENDPOINT points at dynamodb-local under
 * compose and is simply absent in AWS, where the SDK resolves the real
 * regional endpoint. Nothing else branches on environment.
 */
export function createDynamoClient({ region, endpoint } = {}) {
  const client = new DynamoDBClient({
    region: region ?? process.env.AWS_REGION,
    ...(endpoint ? { endpoint } : {}),
  });

  return DynamoDBDocumentClient.from(client, {
    marshallOptions: { removeUndefinedValues: true },
  });
}
