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

    /**
     * PATCH body — only non-null fields are sent (Gson omits nulls by default),
     * and the Profile service applies only the fields present. So a partial
     * update (e.g. avatar only) leaves the other fields untouched.
     */
    public static final class Update {
        public String displayName;
        public String bio;
        public String phone;
        public String visibility;
        public String avatarMediaId;
        public List<String> tags;
        public List<String> links;

        public Update(String displayName, String bio, String phone, String visibility) {
            this.displayName = displayName;
            this.bio = bio;
            this.phone = phone;
            this.visibility = visibility;
        }

        /** Sets ONLY the avatar — other fields stay null (omitted → unchanged). */
        public static Update avatarOnly(String avatarMediaId) {
            Update u = new Update(null, null, null, null);
            u.avatarMediaId = avatarMediaId;
            return u;
        }
    }
}
