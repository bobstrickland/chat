package dev.rstrickland.chat.presence.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Locale;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Reads secrets from AWS Secrets Manager, with the environment as the fallback.
 * The Java counterpart to the Node services' `clients/secretsLoader.js`, driven
 * by the SAME {@code SECRETS_PROVIDER} switch:
 *
 * <ul>
 *   <li><b>env</b> (default, incl. unset/blank) — return the env value unchanged;
 *       local dev is untouched.
 *   <li><b>awssm</b> — fetch the secret and use the named JSON field, falling
 *       back to the env value if it can't be read.
 * </ul>
 *
 * Falling back rather than failing is deliberate: this service VERIFIES the
 * internal API key on its `/internal/presence/...` route. If the value can't be
 * loaded, the env value still guards that one route while every bearer-authed
 * route and the WebSocket handlers keep working — a narrower failure than
 * refusing to start.
 *
 * No caching beyond the value Config holds for the process's lifetime: this runs
 * once at startup, and CLAUDE.md forbids depending on warm-start reuse for
 * correctness. The trade-off is rotation — a rotated secret needs a restart.
 *
 * Duplicated (bar the package) in the messaging service, like KafkaSecurity, for
 * the same reason: there is no shared Java module in this repo.
 */
public final class SecretsLoader {

  public static final String ENV_VAR = "SECRETS_PROVIDER";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SecretsLoader() {}

  /**
   * @param secretId Secrets Manager name or ARN
   * @param jsonField field to read out of the secret's JSON document
   * @param envFallback value from the environment, used when the provider is
   *     `env` or the fetch fails
   * @return the resolved value, or {@code envFallback}
   */
  public static String resolve(String secretId, String jsonField, String envFallback) {
    String raw = System.getenv(ENV_VAR);
    String mode = (raw == null || raw.isBlank()) ? "env" : raw.trim().toLowerCase(Locale.ROOT);

    if ("env".equals(mode)) {
      return envFallback;
    }
    if (!"awssm".equals(mode)) {
      // Fail fast: an unrecognised provider is a deployment mistake, and silently
      // using .env values in AWS is exactly what this feature exists to stop.
      throw new IllegalStateException(ENV_VAR + " must be 'env' or 'awssm', got: '" + raw + "'");
    }

    try (SecretsManagerClient client = buildClient()) {
      String json =
          client
              .getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build())
              .secretString();
      JsonNode node = MAPPER.readTree(json).get(jsonField);
      if (node == null || node.isNull()) {
        System.err.println(
            "[secrets] " + secretId + " has no '" + jsonField + "' — using the environment value");
        return envFallback;
      }
      System.out.println("[secrets] loaded " + secretId + " from Secrets Manager");
      return node.asText();
    } catch (Exception e) {
      System.err.println(
          "[secrets] " + secretId + " unavailable (" + e.getMessage()
              + ") — falling back to the environment");
      return envFallback;
    }
  }

  private static SecretsManagerClient buildClient() {
    var builder =
        SecretsManagerClient.builder()
            .region(Region.of(envOr("AWS_REGION", "us-east-1")));
    // LocalStack in dev; unset in AWS so the SDK resolves the real endpoint.
    String endpoint = System.getenv("SECRETS_MANAGER_ENDPOINT");
    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    return builder.build();
  }

  private static String envOr(String name, String fallback) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? fallback : v;
  }
}
