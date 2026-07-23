package dev.rstrickland.chat.presence.clients;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URL;

/**
 * Verifies Cognito-issued JWTs against the pool's public JWKS — signature only,
 * over HTTPS. This service NEVER imports the Cognito SDK or calls Cognito APIs
 * (CLAUDE.md "Cognito isolation"); nothing here is Cognito-specific beyond the
 * URL that happens to be configured.
 *
 * The subject (`sub`) is normalized out as the app's userId — no provider claim
 * names leak past this boundary.
 *
 * Nimbus's RemoteJWKSet caches keys and refreshes on unknown key IDs, so this
 * is safe to hold as a singleton across warm invocations (and correct on cold
 * ones — it just refetches).
 */
public final class JwksTokenVerifier implements TokenVerifier {

  private final ConfigurableJWTProcessor<SecurityContext> processor;

  public JwksTokenVerifier(String jwksUrl) {
    if (jwksUrl == null || jwksUrl.isBlank()) {
      throw new IllegalArgumentException("COGNITO_JWKS_URL is not configured");
    }
    try {
      JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(new URL(jwksUrl));
      JWSKeySelector<SecurityContext> keySelector =
          new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
      ConfigurableJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
      p.setJWSKeySelector(keySelector);
      this.processor = p;
    } catch (Exception e) {
      throw new IllegalStateException("failed to init JWKS verifier for " + jwksUrl, e);
    }
  }

  @Override
  public String verifyAndGetUserId(String token) throws TokenVerificationException {
    try {
      JWTClaimsSet claims = processor.process(token, null);
      String sub = claims.getSubject();
      if (sub == null || sub.isBlank()) {
        throw new TokenVerificationException("token has no subject", null);
      }
      return sub;
    } catch (TokenVerificationException e) {
      throw e;
    } catch (Exception e) {
      throw new TokenVerificationException("token verification failed", e);
    }
  }
}
