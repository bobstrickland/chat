/**
 * Turn a message.sent event into a search doc and index it.
 *
 * Media-only messages (blank body) carry nothing to full-text search, so they
 * are skipped — the index stays lean and every hit has matchable text. Returns
 * whether it indexed, for observability.
 *
 * @param {{ openSearch: object }} deps
 * @param {{ conversationId, messageId, senderId, body, sentAt }} event
 * @returns {Promise<{ indexed: boolean }>}
 */
export async function indexMessage({ openSearch }, event) {
  if (!event?.messageId || !event.conversationId) {
    throw new Error("message.sent event missing messageId/conversationId");
  }
  const body = (event.body ?? "").trim();
  if (!body) return { indexed: false }; // media-only / empty — nothing to search

  await openSearch.indexMessage({
    messageId: event.messageId,
    conversationId: event.conversationId,
    senderId: event.senderId,
    body,
    sentAt: event.sentAt,
  });
  return { indexed: true };
}
