package dev.rstrickland.chat.messaging.core;

import java.time.Instant;

/**
 * A single chat message within a conversation.
 *
 * @param conversationId owning conversation
 * @param messageId      unique id (UUID)
 * @param senderId       author's userId
 * @param body           text content
 * @param sentAt         server-assigned send time — also drives message ordering
 */
public record Message(
    String conversationId, String messageId, String senderId, String body, Instant sentAt) {}
