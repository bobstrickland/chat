package dev.rstrickland.chat.model;

import java.util.List;

/** Search result shapes from the Search service (Phase 9). */
public final class SearchModels {
    private SearchModels() {}

    /** A message hit — scoped by the backend to the caller's own conversations. */
    public static final class MessageHit {
        public String messageId;
        public String conversationId;
        public String senderId;
        public String body;
        public String sentAt;
    }

    /** A person hit from the public people directory (PUBLIC profiles only). */
    public static final class UserHit {
        public String userId;
        public String displayName;
        public String bio;

        public String label() {
            return (displayName != null && !displayName.trim().isEmpty())
                    ? displayName.trim() : "Unknown";
        }
    }

    public static final class SearchResults {
        public List<MessageHit> messages;
        public List<UserHit> users;
    }
}
