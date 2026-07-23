import { PutCommand, QueryCommand, DeleteCommand } from "@aws-sdk/lib-dynamodb";

/**
 * Owns the `device-tokens` table (PK userId, SK deviceId). One item per
 * registered device/browser.
 *
 * The `platform` attribute (web/ios/android) is what the Notification core
 * branches on for the send mechanism (VAPID/APNs/FCM) — CLAUDE.md Data Model
 * Notes. For web, `subscription` holds the browser PushSubscription (endpoint +
 * keys); for mobile it would hold the APNs/FCM token.
 */
export function createDeviceTokenRepository(docClient, tableName) {
  if (!tableName) {
    throw new Error("DEVICE_TOKENS_TABLE is not configured");
  }

  return {
    /** Upsert — re-registering the same device replaces its subscription. */
    async upsert({ userId, deviceId, platform, subscription }) {
      const now = new Date().toISOString();
      await docClient.send(
        new PutCommand({
          TableName: tableName,
          Item: { userId, deviceId, platform, subscription, updatedAt: now },
        })
      );
      return { userId, deviceId, platform };
    },

    async listForUser(userId) {
      const res = await docClient.send(
        new QueryCommand({
          TableName: tableName,
          KeyConditionExpression: "userId = :u",
          ExpressionAttributeValues: { ":u": userId },
        })
      );
      return res.Items ?? [];
    },

    /** Remove a device — used when a push endpoint reports it's gone (404/410). */
    async remove({ userId, deviceId }) {
      await docClient.send(
        new DeleteCommand({ TableName: tableName, Key: { userId, deviceId } })
      );
    },
  };
}
