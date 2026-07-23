package dev.rstrickland.chat.messaging.core;

/**
 * A user's read/delivered position in a conversation — "user X has {kind} all
 * messages up to {position}". Position-based rather than per-message: one item
 * per (conversation, user, kind), advanced as they read/receive.
 *
 * @param userId   whose position this is
 * @param kind     "delivered" | "read"
 * @param position the sentAt (ISO-8601) of the newest message covered
 */
public record Receipt(String userId, String kind, String position) {}
