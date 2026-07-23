package dev.rstrickland.chat.messaging.core;

import java.util.List;

/**
 * Persistence boundary for the single-table Conversations design (CLAUDE.md Data
 * Model Notes): PK conversationId, SK one of meta / member#{userId} /
 * ts#{sentAt}#{messageId}. Implemented by clients/ against DynamoDB.
 */
public interface ConversationRepository {

  /**
   * Create the conversation's meta + member items if absent. Idempotent: called
   * on every send, but only writes on the first. Members are stable for a
   * direct conversation.
   */
  void ensureDirectConversation(String conversationId, String userA, String userB);

  /** Create a group conversation: meta (with name) + a member item per member. */
  void createGroup(String conversationId, String name, String createdBy, java.util.List<String> members);

  /** Conversation meta (type + name), or null if the conversation doesn't exist. */
  ConversationMeta meta(String conversationId);

  void saveMessage(Message message);

  /** Messages in chronological order (oldest first), newest-capped at limit. */
  List<Message> listMessages(String conversationId, int limit);

  /** userIds of the conversation's members — the fan-out set for delivery. */
  List<String> members(String conversationId);

  /** conversationIds this user belongs to (via gsi-user-conversations). */
  List<String> userConversations(String userId);

  /** The most recent message in a conversation, or null if there are none yet. */
  Message lastMessage(String conversationId);

  /**
   * Advance a user's receipt position. Only moves forward — a late/duplicate
   * receipt for an older position must not roll it back.
   */
  void upsertReceipt(String conversationId, String kind, String userId, String position);

  /** All receipt positions (delivered/read, per user) for a conversation. */
  List<Receipt> receipts(String conversationId);
}
