/**
 * A new account has no display name yet, and the chat UI needs *something*
 * renderable immediately. The local-part of the email is the least-surprising
 * default; the user can change it via PATCH.
 *
 * For federated (Google) users the bearer is an access token, which carries no
 * email claim — so this usually falls back to "New User" for them. That's fine:
 * the point is a non-empty default they can edit, not a perfect name.
 *
 * Profile never STORES the email — email remains Auth's data (CLAUDE.md
 * "No shared databases"); it's only read here to seed a default.
 */
export function defaultDisplayName(email) {
  if (!email || typeof email !== "string" || !email.includes("@")) {
    return "New User";
  }
  return email.split("@")[0].slice(0, 64) || "New User";
}
