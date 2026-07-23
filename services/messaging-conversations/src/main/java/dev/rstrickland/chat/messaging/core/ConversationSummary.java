package dev.rstrickland.chat.messaging.core;

/**
 * A row in a user's conversation list: which conversation, who it's with, and a
 * preview of the latest message (null if the conversation has no messages yet).
 *
 * @param conversationId the conversation
 * @param peerId         the OTHER participant (direct conversations only, for now)
 * @param lastMessage    most recent message, or null
 */
public record ConversationSummary(String conversationId, String peerId, Message lastMessage) {}
