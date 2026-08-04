package dev.rstrickland.chat.messaging.clients;

import dev.rstrickland.chat.messaging.core.ConnectionPusher;
import java.util.Locale;

/**
 * Chooses the {@link ConnectionPusher} implementation. One knob, {@code
 * WS_PROVIDER}:
 *
 * <ul>
 *   <li><b>shim</b> (default, and when unset) — {@link WsShimConnectionPusher}
 *       against local ws-shim.
 *   <li><b>apigateway</b> — {@link ApiGatewayConnectionPusher} against the real
 *       @connections management API.
 * </ul>
 *
 * Unlike {@code KAFKA_AUTH}, an unrecognised value here throws instead of
 * falling back to the default. The reason is the failure mode: a mistyped value
 * that quietly selected the shim in AWS would leave every push trying to reach a
 * host that doesn't exist, and pushes fail SILENTLY by design (a failed push
 * returns false so one dead connection can't break a fan-out). That would look
 * like "messages sometimes don't arrive" rather than a startup error — the worst
 * possible way to learn about a typo.
 */
public final class ConnectionPushers {

  public static final String ENV_VAR = "WS_PROVIDER";

  private ConnectionPushers() {}

  /**
   * @param mode {@code shim} | {@code apigateway}; null/blank means shim
   * @param shimEndpoint {@code WS_SHIM_ENDPOINT} — required for shim only
   * @param shimManagePath {@code WS_SHIM_MANAGE_CONNECTIONS_PATH}
   * @param region {@code AWS_REGION} — used by apigateway only
   * @param managementEndpoint {@code WS_MANAGEMENT_ENDPOINT} — apigateway only
   */
  public static ConnectionPusher create(
      String mode,
      String shimEndpoint,
      String shimManagePath,
      String region,
      String managementEndpoint) {
    String normalized =
        (mode == null || mode.isBlank()) ? "shim" : mode.trim().toLowerCase(Locale.ROOT);
    switch (normalized) {
      case "shim":
        return new WsShimConnectionPusher(shimEndpoint, shimManagePath);
      case "apigateway":
        return new ApiGatewayConnectionPusher(region, managementEndpoint);
      default:
        throw new IllegalStateException(
            ENV_VAR + " must be 'shim' or 'apigateway', got: '" + mode + "'");
    }
  }
}
