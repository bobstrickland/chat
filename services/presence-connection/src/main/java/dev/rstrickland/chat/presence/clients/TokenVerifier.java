package dev.rstrickland.chat.presence.clients;

/** Verifies a bearer/JWT and returns the subject (userId). Throws if invalid. */
public interface TokenVerifier {
  String verifyAndGetUserId(String token) throws TokenVerificationException;

  class TokenVerificationException extends Exception {
    public TokenVerificationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
