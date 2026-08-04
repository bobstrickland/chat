package dev.rstrickland.chat.messaging.clients;

import java.util.Locale;
import java.util.Properties;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;

/**
 * Kafka transport security, applied to every producer and consumer this service
 * builds.
 *
 * Two modes, chosen by the {@code KAFKA_AUTH} env var:
 *
 * <ul>
 *   <li><b>plaintext</b> (default, and what happens when the var is unset) —
 *       adds nothing. Local Redpanda listens plaintext with no auth, so the
 *       local stack keeps working untouched.
 *   <li><b>iam</b> — SASL_SSL + {@code AWS_MSK_IAM}, which is what a real MSK
 *       cluster requires: {@code terraform/modules/msk/main.tf} sets
 *       {@code client_broker = "TLS"} and {@code sasl { iam = true }}, so a
 *       plaintext client cannot connect to it at all.
 * </ul>
 *
 * Defaulting to plaintext rather than IAM is deliberate: the failure mode of a
 * wrong default should be "doesn't work in AWS until you set one env var", not
 * "doesn't work on any developer's machine".
 *
 * Credentials come from the default AWS provider chain — the Lambda execution
 * role or ECS task role in AWS (the per-service roles in
 * {@code terraform/modules/iam} already carry the MSK grants). To assume a
 * different role instead, the login module accepts options in the JAAS config,
 * e.g. {@code ... required awsRoleArn="arn:...";} — not wired up, since no
 * deployment needs it yet.
 *
 * This class is duplicated verbatim (bar the package) in the presence and media
 * services. There is no shared Java module in this repo — each service owns its
 * own tree and pom — so the alternative would be inventing one for ~15 lines of
 * configuration. If a fourth Java service appears, reconsider.
 */
public final class KafkaSecurity {

  /** Env var selecting the mode. Same name in the Node services. */
  public static final String ENV_VAR = "KAFKA_AUTH";

  private static final String IAM = "iam";
  private static final String LOGIN_MODULE = "software.amazon.msk.auth.iam.IAMLoginModule";
  private static final String CALLBACK_HANDLER =
      "software.amazon.msk.auth.iam.IAMClientCallbackHandler";

  private KafkaSecurity() {}

  /** The configured mode, lowercased; "plaintext" when unset or blank. */
  public static String modeFromEnv() {
    String raw = System.getenv(ENV_VAR);
    return (raw == null || raw.isBlank()) ? "plaintext" : raw.trim().toLowerCase(Locale.ROOT);
  }

  /** Applies the env-configured mode. */
  public static void apply(Properties props) {
    apply(props, modeFromEnv());
  }

  /**
   * Applies {@code mode} to {@code props}. Separate from {@link #modeFromEnv()}
   * so the mapping is a pure function and can be unit tested without touching
   * the environment.
   */
  public static void apply(Properties props, String mode) {
    if (!IAM.equals(mode)) {
      return; // plaintext — nothing to add
    }
    props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
    props.put(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
    props.put(SaslConfigs.SASL_JAAS_CONFIG, LOGIN_MODULE + " required;");
    props.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS, CALLBACK_HANDLER);
  }
}
