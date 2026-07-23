package dev.rstrickland.chat.messaging.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("body is required");
    }
    if (body.length() > MAX_BODY) {
      throw new IllegalArgumentException("body exceeds " + MAX_BODY + " characters");
    }

    String conversationId = directConversationId(senderId, recipientId);
    repository.ensureDirectConversation(conversationId, senderId, recipientId);

    Message message =
        new Message(conversationId, UUID.randomUUID().toString(), senderId, body.strip(), Instant.now());
    repository.saveMessage(message);
    publisher.messageSent(message);
    return message;
  }

  /** History of the direct conversation between the caller and a peer. */
  public List<Message> directHistory(String userId, String peerId, int limit) {
    return repository.listMessages(directConversationId(userId, peerId), limit);
  }

  /**
   * The user's conversation list, most-recently-active first. For each
   * conversation the peer is derived from the deterministic id (no extra query),
   * and the last message is fetched for a preview.
   */
  public List<ConversationSummary> listConversations(String userId) {
    List<ConversationSummary> out = new ArrayList<>();
    for (String conversationId : repository.userConversations(userId)) {
      out.add(
          new ConversationSummary(
              conversationId,
              directPeer(conversationId, userId),
              repository.lastMessage(conversationId)));
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
