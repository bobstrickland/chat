package dev.rstrickland.chat.messaging.core;

import java.time.Instant;

/**
 * A single chat message within a conversation.
 *
 * @param conversationId owning conversation
 * @param messageId      unique id (UUID)
 * @param senderId       author's userId
 * @param body           text content (may be blank for a media-only message)
 * @param sentAt         server-assigned send time — also drives message ordering
 * @param mediaId        attached media (Media service id), or null for text-only
 * @param deleted        true if deleted-for-everyone (Phase 12): body/media cleared,
 *                       the item kept as a tombstone so ordering + a "deleted" marker survive
 */
public record Message(
    String conversationId,
    String messageId,
    String senderId,
    String body,
    Instant sentAt,
    String mediaId,
    boolean deleted) {

  /** Convenience for the common non-deleted case. */
  public Message(
      String conversationId, String messageId, String senderId, String body, Instant sentAt, String mediaId) {
    this(conversationId, messageId, senderId, body, sentAt, mediaId, false);
  }
}
