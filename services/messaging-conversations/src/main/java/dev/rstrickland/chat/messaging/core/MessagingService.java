package dev.rstrickland.chat.messaging.core;

import java.time.Instant;
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
}
