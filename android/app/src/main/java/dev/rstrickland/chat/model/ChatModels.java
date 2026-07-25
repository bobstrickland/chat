package dev.rstrickland.chat.model;

import java.util.List;

/** Conversation + message shapes from the Messaging service. */
public final class ChatModels {
    private ChatModels() {}

    public static final class ConversationRow {
        public String conversationId;
        public String type;        // "direct" | "group"
        public String name;        // group name (null for direct)
        public String peerId;      // other participant (direct only)
        public Message lastMessage;

        /** A display title: group name, or the peer id for a direct (names come later). */
        public String title() {
            if ("group".equals(type)) return name != null ? name : "Group";
            return peerId != null ? peerId : conversationId;
        }
    }

    public static final class ConversationsResponse {
        public List<ConversationRow> conversations;
    }

    public static final class Message {
        public String conversationId;
        public String messageId;
        public String senderId;
        public String body;
        public String sentAt;
        public String mediaId;
        public boolean deleted;   // Phase 12 tombstone
    }

    public static final class HistoryResponse {
        public String conversationId;
        public List<Message> messages;
    }

    public static final class SendRequest {
        public String body;
        public String mediaId;

        public SendRequest(String body, String mediaId) {
            this.body = body;
            this.mediaId = mediaId;
        }
    }
}
