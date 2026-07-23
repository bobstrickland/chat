package dev.rstrickland.chat.messaging.core;

import java.util.List;

/**
 * Pushes a receipt update to every connection of every member of a conversation
 * — pure, given the connection interfaces. This is the "push receipt updates to
 * all of a user's active connections" from the Phase 7 plan: the sender's
 * devices update their read/delivered indicators, and the reader's OTHER devices
 * sync their read position (so unread clears everywhere at once).
 *
 * Unlike DeliveryService this includes the actor's own devices deliberately —
 * that's the multi-device read-sync path. Receipts are also fire-and-forget:
 * they're advisory UI state, so an offline member simply misses this update and
 * re-syncs from the receipt positions on next history load.
 */
public final class ReceiptBroadcaster {

  private final ConnectionLookup connections;
  private final ConnectionPusher pusher;
  private final FrameWriter frameWriter;

  /** Serializes a receipt to the JSON frame pushed to clients. */
  public interface FrameWriter {
    String toFrame(String conversationId, String userId, String kind, String position);
  }

  public ReceiptBroadcaster(ConnectionLookup connections, ConnectionPusher pusher, FrameWriter frameWriter) {
    this.connections = connections;
    this.pusher = pusher;
    this.frameWriter = frameWriter;
  }

  public void broadcast(
      String conversationId, String actorId, String kind, String position, List<String> members) {
    String frame = frameWriter.toFrame(conversationId, actorId, kind, position);
    for (String member : members) {
      for (String connectionId : connections.activeConnections(member)) {
        pusher.push(connectionId, frame);
      }
    }
  }
}
