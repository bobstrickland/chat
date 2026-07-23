import {
  PutCommand,
  GetCommand,
  UpdateCommand,
  DeleteCommand,
} from "@aws-sdk/lib-dynamodb";

/**
 * Owns the `profiles` table (PK `userId`), per
 * terraform/modules/dynamodb/main.tf and scripts/init_dynamodb.sh.
 *
 * This table belongs to the Profile Service alone. No other service reads it
 * directly — they call this service's API (CLAUDE.md "No shared databases").
 *
 * Note there is deliberately no `list`/`scan`: there's no GSI on this table,
 * and scanning a user table is a performance trap. Profile discovery/search
 * is Search Service's job (Phase 9), fed by an indexing pipeline.
 */
export function createProfileRepository(docClient, tableName) {
  if (!tableName) {
    throw new Error("PROFILES_TABLE is not configured");
  }

  return {
    /**
     * Create-if-absent. The condition makes provisioning idempotent: a
     * retried postConfirmation (Cognito retries triggers) won't overwrite a
     * profile the user has since edited.
     * @returns {Promise<{created: boolean, profile: object}>}
     */
    async createIfAbsent({ userId, displayName }) {
      const now = new Date().toISOString();
      const profile = {
        userId,
        displayName,
        avatarUrl: null,
        bio: null,
        createdAt: now,
        updatedAt: now,
      };

      try {
        await docClient.send(
          new PutCommand({
            TableName: tableName,
            Item: profile,
            ConditionExpression: "attribute_not_exists(userId)",
          })
        );
        return { created: true, profile };
      } catch (err) {
        if (err.name === "ConditionalCheckFailedException") {
          return { created: false, profile: await this.get({ userId }) };
        }
        throw err;
      }
    },

    async get({ userId }) {
      const result = await docClient.send(
        new GetCommand({ TableName: tableName, Key: { userId } })
      );
      return result.Item ?? null;
    },

    /**
     * Patch semantics — only the supplied fields change. Guarded so it can
     * never resurrect a deleted profile as a partial row.
     */
    async update({ userId, fields }) {
      const entries = Object.entries(fields);
      if (entries.length === 0) {
        throw new Error("no updatable fields supplied");
      }

      const names = { "#updatedAt": "updatedAt" };
      const values = { ":updatedAt": new Date().toISOString() };
      const sets = ["#updatedAt = :updatedAt"];

      for (const [key, value] of entries) {
        names[`#${key}`] = key;
        values[`:${key}`] = value;
        sets.push(`#${key} = :${key}`);
      }

      const result = await docClient.send(
        new UpdateCommand({
          TableName: tableName,
          Key: { userId },
          UpdateExpression: `SET ${sets.join(", ")}`,
          ExpressionAttributeNames: names,
          ExpressionAttributeValues: values,
          ConditionExpression: "attribute_exists(userId)",
          ReturnValues: "ALL_NEW",
        })
      );
      return result.Attributes;
    },

    async remove({ userId }) {
      await docClient.send(
        new DeleteCommand({ TableName: tableName, Key: { userId } })
      );
    },
  };
}
