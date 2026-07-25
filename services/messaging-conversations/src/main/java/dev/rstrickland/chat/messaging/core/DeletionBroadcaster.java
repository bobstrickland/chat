package dev.rstrickland.chat.messaging.core;

import java.util.List;

/**
 * Pushes a "message deleted for everyone" frame to every connection of every
 * member of a conversation (Phase 12) — the live counterpart to the tombstone
 * written in the table, so open clients drop/replace the message immediately
 * instead of only on next history load.
 *
 * Same shape as ReceiptBroadcaster (fire-and-forget; an offline member simply
 * sees the tombstone when they next load history).
 */
public final class DeletionBroadcaster {

  private final ConnectionLookup connections;
  private final ConnectionPusher pusher;
  private final FrameWriter frameWriter;

  /** Serializes the deletion into the JSON frame pushed to clients. */
  public interface FrameWriter {
    String toDeletedFrame(String conversationId, String messageId);
  }

  public DeletionBroadcaster(ConnectionLookup connections, ConnectionPusher pusher, FrameWriter frameWriter) {
    this.connections = connections;
    this.pusher = pusher;
    this.frameWriter = frameWriter;
  }

  public void broadcast(String conversationId, String messageId, List<String> members) {
    String frame = frameWriter.toDeletedFrame(conversationId, messageId);
    for (String member : members) {
      for (String connectionId : connections.activeConnections(member)) {
        pusher.push(connectionId, frame);
      }
    }
  }
}
