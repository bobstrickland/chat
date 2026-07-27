package dev.rstrickland.chat.model;

import java.util.List;

/** Contact shapes from the Profile service (Phase 11). Contacts are self-only. */
public final class ContactModels {
    private ContactModels() {}

    /** A contact row: the added user's basic identity, enriched by the server. */
    public static final class Contact {
        public String userId;
        public String displayName;
        public String avatarMediaId;
        public String addedAt;

        /** A display label — display name, never the raw userId (per the name rule). */
        public String label() {
            return (displayName != null && !displayName.trim().isEmpty())
                    ? displayName.trim() : "Unknown";
        }
    }

    public static final class ContactsResponse {
        public List<Contact> contacts;
    }

    /** POST /contacts body. */
    public static final class AddRequest {
        public String contactId;

        public AddRequest(String contactId) {
            this.contactId = contactId;
        }
    }
}
