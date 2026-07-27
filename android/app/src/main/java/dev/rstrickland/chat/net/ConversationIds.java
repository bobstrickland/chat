package dev.rstrickland.chat.net;

/**
 * Deterministic conversation ids, matching the Messaging service + web client.
 * A 1:1 conversation is {@code dm#{min}#{max}} of the two userIds (lexicographic),
 * so both participants derive the same id without a lookup.
 */
public final class ConversationIds {
    private ConversationIds() {}

    /** The direct conversation id shared by users {@code a} and {@code b}. */
    public static String direct(String a, String b) {
        if (a == null || b == null) throw new IllegalArgumentException("both userIds required");
        String lo = a.compareTo(b) <= 0 ? a : b;
        String hi = a.compareTo(b) <= 0 ? b : a;
        return "dm#" + lo + "#" + hi;
    }

    public static boolean isGroup(String conversationId) {
        return conversationId != null && conversationId.startsWith("grp#");
    }

    /** For a direct id, the participant that is NOT {@code me} (null if not derivable). */
    public static String peerOf(String conversationId, String me) {
        if (conversationId == null || !conversationId.startsWith("dm#")) return null;
        String[] parts = conversationId.split("#", 3);
        if (parts.length != 3) return null;
        if (parts[1].equals(me)) return parts[2];
        if (parts[2].equals(me)) return parts[1];
        return parts[1]; // caller isn't a participant we recognize; best-effort
    }
}
