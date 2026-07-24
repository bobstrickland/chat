import {
  PutCommand,
  DeleteCommand,
  GetCommand,
  QueryCommand,
} from "@aws-sdk/lib-dynamodb";

/**
 * Owns the `contacts` table (PK `userId` = owner, SK `contactId` = user added),
 * part of the Profile service (Phase 11). One item per (owner, contact).
 *
 * - list(userId)            → everyone `userId` has added
 * - isContact(userId, x)    → did `userId` add `x`?  (the CONTACTS-visibility check:
 *                             owner controls who sees their contacts-only profile)
 */
export function createContactRepository(docClient, tableName) {
  if (!tableName) {
    throw new Error("CONTACTS_TABLE is not configured");
  }

  return {
    async add({ userId, contactId }) {
      await docClient.send(
        new PutCommand({
          TableName: tableName,
          Item: { userId, contactId, createdAt: new Date().toISOString() },
        })
      );
    },

    async remove({ userId, contactId }) {
      await docClient.send(
        new DeleteCommand({ TableName: tableName, Key: { userId, contactId } })
      );
    },

    async isContact({ userId, contactId }) {
      const result = await docClient.send(
        new GetCommand({ TableName: tableName, Key: { userId, contactId } })
      );
      return result.Item != null;
    },

    /** @returns {Promise<Array<{contactId: string, createdAt: string}>>} */
    async list({ userId }) {
      const result = await docClient.send(
        new QueryCommand({
          TableName: tableName,
          KeyConditionExpression: "userId = :u",
          ExpressionAttributeValues: { ":u": userId },
        })
      );
      return (result.Items ?? []).map((i) => ({
        contactId: i.contactId,
        createdAt: i.createdAt,
      }));
    },
  };
}
