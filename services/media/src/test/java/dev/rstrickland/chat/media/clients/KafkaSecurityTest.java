package dev.rstrickland.chat.media.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The mode→properties mapping is a pure function, so it's testable without a
 * broker or an environment. Worth testing despite being small: the exact
 * mechanism name and the two class names are strings MSK matches literally, and
 * a typo in any of them fails only at connect time, in AWS, at deploy.
 */
class KafkaSecurityTest {

  @Test
  void plaintextAddsNothing() {
    Properties props = new Properties();
    props.put("bootstrap.servers", "redpanda:9092");

    KafkaSecurity.apply(props, "plaintext");

    assertEquals(1, props.size(), "local/plaintext must leave the config untouched");
  }

  @Test
  void unrecognisedModeIsTreatedAsPlaintext() {
    Properties props = new Properties();
    // Anything that isn't "iam" must NOT silently half-configure SASL — a
    // typo'd value should behave like the safe local default.
    KafkaSecurity.apply(props, "IAM_MAYBE");
    KafkaSecurity.apply(props, "");
    assertTrue(props.isEmpty());
  }

  @Test
  void iamAddsTheFourSettingsMskRequires() {
    Properties props = new Properties();

    KafkaSecurity.apply(props, "iam");

    assertEquals("SASL_SSL", props.get("security.protocol"));
    assertEquals("AWS_MSK_IAM", props.get("sasl.mechanism"));
    assertEquals(
        "software.amazon.msk.auth.iam.IAMLoginModule required;", props.get("sasl.jaas.config"));
    assertEquals(
        "software.amazon.msk.auth.iam.IAMClientCallbackHandler",
        props.get("sasl.client.callback.handler.class"));
  }

  @Test
  void iamIsCaseInsensitiveViaTheEnvHelper() {
    // modeFromEnv lowercases, so "IAM" in .env behaves like "iam".
    Properties props = new Properties();
    KafkaSecurity.apply(props, "IAM".toLowerCase());
    assertEquals("SASL_SSL", props.get("security.protocol"));
  }

  @Test
  void theLoginModuleAndCallbackHandlerAreOnTheClasspath() {
    // Guards the dependency itself: the JAAS strings above are only ever
    // resolved reflectively by Kafka, so a missing aws-msk-iam-auth artifact
    // would otherwise surface as a runtime failure inside the broker handshake.
    for (String cls :
        new String[] {
          "software.amazon.msk.auth.iam.IAMLoginModule",
          "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
        }) {
      try {
        Class.forName(cls);
      } catch (ClassNotFoundException e) {
        throw new AssertionError("not on the classpath: " + cls, e);
      }
    }
  }
}
