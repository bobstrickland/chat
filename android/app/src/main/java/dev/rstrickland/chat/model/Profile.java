package dev.rstrickland.chat.model;

import java.util.List;

/** Mirrors the Profile service shape (Phase 10/11 fields included). */
public final class Profile {
    public String userId;
    public String displayName;
    public String avatarMediaId;
    public String bio;
    public String phone;
    public List<String> links;
    public List<String> tags;
    public String visibility;   // PUBLIC | CONTACTS | PRIVATE
    public Boolean restricted;  // set when viewing a restricted other-user profile

    /** PATCH body — only non-null fields are sent (Gson omits nulls by default). */
    public static final class Update {
        public String displayName;
        public String bio;
        public String phone;
        public String visibility;

        public Update(String displayName, String bio, String phone, String visibility) {
            this.displayName = displayName;
            this.bio = bio;
            this.phone = phone;
            this.visibility = visibility;
        }
    }
}
