package dev.rstrickland.chat.messaging.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Send + read logic — pure. Persists the message and publishes message.sent;
 * it does NOT deliver (that's DeliveryService, driven off the event). Keeping
 * send and delivery separate is what lets delivery, notification (Phase 5) and
 * search (Phase 9) all hang off message.sent independently.
 */
public final class MessagingService {

  private static final int MAX_BODY = 4000;

  private final ConversationRepository repository;
  private final MessageEventPublisher publisher;

  public MessagingService(ConversationRepository repository, MessageEventPublisher publisher) {
    this.repository = repository;
    this.publisher = publisher;
  }

  /**
   * Deterministic id for the 1:1 conversation between two users. Sorting the
   * pair means both participants derive the SAME id from either direction, so a
   * direct conversation is unique without a lookup.
   */
  public static String directConversationId(String userA, String userB) {
    String lo = userA.compareTo(userB) <= 0 ? userA : userB;
    String hi = userA.compareTo(userB) <= 0 ? userB : userA;
    return "dm#" + lo + "#" + hi;
  }

  public Message sendDirect(String senderId, String recipientId, String body) {
    if (recipientId == null || recipientId.isBlank()) {
      throw new IllegalArgumentException("recipientId is required");
    }
    if (senderId.equals(recipientId)) {
      throw new IllegalArgumentException("cannot send a message to yourself");
    }
    return sendToConversation(senderId, directConversationId(senderId, recipientId), body);
  }

  /**
   * Create a group conversation. The creator is always a member. Returns the new
   * conversationId (`grp#{uuid}`).
   */
  public String createGroup(String creatorId, String name, List<String> memberIds) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("group name is required");
    }
    if (memberIds == null || memberIds.isEmpty()) {
      throw new IllegalArgumentException("a group needs at least one other member");
    }
    // Dedupe and always include the creator; order-stable for readability.
    LinkedHashSet<String> members = new LinkedHashSet<>();
    members.add(creatorId);
    for (String m : memberIds) {
      if (m != null && !m.isBlank()) {
        members.add(m.strip());
      }
    }
    if (members.size() < 2) {
      throw new IllegalArgumentException("a group needs at least one member besides you");
    }

    String conversationId = "grp#" + UUID.randomUUID();
    repository.createGroup(conversationId, name.strip(), creatorId, new ArrayList<>(members));
    return conversationId;
  }

  /**
   * Unified send. Works for direct (`dm#`) and group (`grp#`) conversations:
   *   - direct: derive the two participants from the id, verify the sender is
   *     one of them, and auto-create the conversation on first message.
   *   - group: the conversation must exist and the sender must be a member.
   * The subsequent delivery fan-out (DeliveryService) already handles any number
   * of members, so groups need no special delivery path.
   */
  public Message sendToConversation(String senderId, String conversationId, String body) {
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("body is required");
    }
    if (body.length() > MAX_BODY) {
      throw new IllegalArgumentException("body exceeds " + MAX_BODY + " characters");
    }
    requireMembership(senderId, conversationId, /* autoCreateDirect= */ true);

    Message message =
        new Message(conversationId, UUID.randomUUID().toString(), senderId, body.strip(), Instant.now());
    repository.saveMessage(message);
    publisher.messageSent(message);
    return message;
  }

  /** History of any conversation the caller is a member of. */
  public List<Message> conversationHistory(String userId, String conversationId, int limit) {
    requireMembership(userId, conversationId, /* autoCreateDirect= */ false);
    return repository.listMessages(conversationId, limit);
  }

  /** Current receipt positions (delivered/read, per user) for a conversation. */
  public List<Receipt> conversationReceipts(String userId, String conversationId) {
    requireMembership(userId, conversationId, /* autoCreateDirect= */ false);
    return repository.receipts(conversationId);
  }

  /**
   * Record that the caller has delivered/read a conversation up to `position`
   * (the sentAt of the newest message they've received/seen). Returns the
   * conversation's members so the adapter can fan the receipt out to all their
   * connections — including the caller's OWN other devices (read-state sync).
   */
  public List<String> recordReceipt(String userId, String conversationId, String kind, String position) {
    if (!"delivered".equals(kind) && !"read".equals(kind)) {
      throw new IllegalArgumentException("kind must be 'delivered' or 'read'");
    }
    if (position == null || position.isBlank()) {
      throw new IllegalArgumentException("position is required");
    }
    requireMembership(userId, conversationId, /* autoCreateDirect= */ false);
    repository.upsertReceipt(conversationId, kind, userId, position);
    return repository.members(conversationId);
  }

  /** History of the direct conversation between the caller and a peer. */
  public List<Message> directHistory(String userId, String peerId, int limit) {
    return conversationHistory(userId, directConversationId(userId, peerId), limit);
  }

  /**
   * Authorize a user against a conversation. For a direct id, membership is
   * implicit in the id (the two participants) and the conversation is
   * auto-created on a first send. For a group, membership is looked up.
   * Throws IllegalStateException (→ 403) when the user isn't allowed.
   */
  private void requireMembership(String userId, String conversationId, boolean autoCreateDirect) {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("conversationId is required");
    }
    if (conversationId.startsWith("dm#")) {
      String[] parts = conversationId.split("#");
      if (parts.length != 3 || (!parts[1].equals(userId) && !parts[2].equals(userId))) {
        throw new IllegalStateException("not a participant in this conversation");
      }
      if (autoCreateDirect) {
        repository.ensureDirectConversation(conversationId, parts[1], parts[2]);
      }
      return;
    }
    if (conversationId.startsWith("grp#")) {
      List<String> members = repository.members(conversationId);
      if (members.isEmpty()) {
        throw new IllegalStateException("conversation not found");
      }
      if (!members.contains(userId)) {
        throw new IllegalStateException("not a member of this group");
      }
      return;
    }
    throw new IllegalArgumentException("unrecognized conversationId");
  }

  /**
   * The user's conversation list, most-recently-active first. For each
   * conversation the peer is derived from the deterministic id (no extra query),
   * and the last message is fetched for a preview.
   */
  public List<ConversationSummary> listConversations(String userId) {
    List<ConversationSummary> out = new ArrayList<>();
    for (String conversationId : repository.userConversations(userId)) {
      Message last = repository.lastMessage(conversationId);
      if (conversationId.startsWith("grp#")) {
        // Group: read meta for the name (directs skip this query — peer comes
        // straight from the id).
        ConversationMeta meta = repository.meta(conversationId);
        out.add(
            new ConversationSummary(
                conversationId, "group", meta == null ? null : meta.name(), null, last));
      } else {
        out.add(
            new ConversationSummary(
                conversationId, "direct", null, directPeer(conversationId, userId), last));
      }
    }
    // Most recent first; conversations with no messages sink to the bottom.
    out.sort(
        Comparator.comparing(
                (ConversationSummary c) ->
                    c.lastMessage() == null ? Instant.EPOCH : c.lastMessage().sentAt())
            .reversed());
    return out;
  }

  /**
   * The other participant in a direct conversation id `dm#{a}#{b}`. Returns null
   * for a non-direct id (groups, Phase 6) — the caller decides how to render those.
   */
  public static String directPeer(String conversationId, String userId) {
    if (!conversationId.startsWith("dm#")) {
      return null;
    }
    String[] parts = conversationId.split("#");
    if (parts.length != 3) {
      return null;
    }
    return parts[1].equals(userId) ? parts[2] : parts[1];
  }
}
