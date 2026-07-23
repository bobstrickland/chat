package dev.rstrickland.chat.messaging.core;

/**
 * The `meta` item of a conversation. `name` is set for groups; direct
 * conversations have a null name (their "name" is the other participant,
 * derived from the id).
 *
 * @param type "direct" | "group"
 * @param name group name, or null for a direct conversation
 */
public record ConversationMeta(String type, String name) {}
