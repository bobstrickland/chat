package dev.rstrickland.chat.messaging.core;

/**
 * A row in a user's conversation list.
 *
 * @param conversationId the conversation
 * @param type           "direct" | "group"
 * @param name           group name (null for direct)
 * @param peerId         the OTHER participant (direct only; null for group)
 * @param lastMessage    most recent message, or null
 */
public record ConversationSummary(
    String conversationId, String type, String name, String peerId, Message lastMessage) {}
