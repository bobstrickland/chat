import { PutCommand, GetCommand, UpdateCommand } from "@aws-sdk/lib-dynamodb";

/**
 * Auth's own record of who exists. Keyed by `email` (PK) to match
 * terraform/modules/dynamodb/main.tf and scripts/init_dynamodb.sh.
 *
 * Deliberately thin: this is an identity ledger, NOT a profile store.
 * Display names, avatars, and anything user-editable belong to the Profile
 * Service (Phase 2) — do not grow this table into a profile table, that
 * would violate the no-shared-databases rule from the other direction.
 *
 * Cognito remains the source of truth for authentication. This table exists
 * so Auth can answer "does this email map to a userId, and what's its
 * registration state" without a Cognito admin API round-trip.
 */
export function createUserRepository(docClient, tableName) {
  if (!tableName) {
    throw new Error("USERS_TABLE is not configured");
  }

  return {
    /**
     * Idempotent by construction: PutCommand overwrites on the same email,
     * so a retried registration converges rather than erroring.
     */
    async putUser({ email, userId, status }) {
      const now = new Date().toISOString();
      await docClient.send(
        new PutCommand({
          TableName: tableName,
          Item: { email, userId, status, createdAt: now, updatedAt: now },
        })
      );
      return { email, userId, status };
    },

    async getUser({ email }) {
      const result = await docClient.send(
        new GetCommand({ TableName: tableName, Key: { email } })
      );
      return result.Item ?? null;
    },

    /**
     * Used by the postConfirmation trigger to flip UNCONFIRMED → CONFIRMED.
     * Only touches `status`, so it can't clobber a concurrent registration
     * write. Creates the item if registration's write was lost — see the
     * reconciliation note in core/register.js.
     */
    async markConfirmed({ email, userId }) {
      const result = await docClient.send(
        new UpdateCommand({
          TableName: tableName,
          Key: { email },
          UpdateExpression:
            "SET #status = :confirmed, updatedAt = :now, userId = if_not_exists(userId, :userId), createdAt = if_not_exists(createdAt, :now)",
          ExpressionAttributeNames: { "#status": "status" },
          ExpressionAttributeValues: {
            ":confirmed": "CONFIRMED",
            ":now": new Date().toISOString(),
            ":userId": userId,
          },
          ReturnValues: "ALL_NEW",
        })
      );
      return result.Attributes;
    },
  };
}
