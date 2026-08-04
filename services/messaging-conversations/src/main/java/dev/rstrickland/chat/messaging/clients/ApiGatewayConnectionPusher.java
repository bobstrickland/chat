package dev.rstrickland.chat.messaging.clients;

import dev.rstrickland.chat.messaging.core.ConnectionPusher;
import java.net.URI;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;

/**
 * Pushes to a WebSocket connection via API Gateway's @connections management API
 * — the real-AWS counterpart to {@link WsShimConnectionPusher}. Same
 * {@link ConnectionPusher} contract, so delivery, receipts and deletions are
 * untouched by the swap: this is the "endpoint/adapter swap only, never a logic
 * change" rule in practice.
 *
 * The endpoint is NOT the wss:// URL clients connect to. It's the HTTPS
 * management endpoint of the same API and stage:
 *
 *   https://{api-id}.execute-api.{region}.amazonaws.com/{stage}
 *
 * The SDK appends {@code /@connections/{connectionId}} itself. Passing the wss://
 * URL, or omitting the stage, produces confusing 403s rather than a clear error,
 * so both are validated up front.
 *
 * Requires {@code execute-api:ManageConnections} on
 * {@code arn:aws:execute-api:{region}:{account}:*}/*}/POST/@connections/*} —
 * already granted to every service role by {@code terraform/modules/iam}
 * ({@code ws_manage_connections}).
 *
 * Note there is no HTTP/1.1 pinning here, unlike the shim client: that was a
 * workaround for ws-shim's Node server misreading the JDK client's h2c upgrade
 * header. Real API Gateway has no such quirk, and this goes through the AWS SDK
 * anyway.
 */
public final class ApiGatewayConnectionPusher implements ConnectionPusher {

  private final ApiGatewayManagementApiClient client;

  public ApiGatewayConnectionPusher(String region, String managementEndpoint) {
    this(buildClient(region, managementEndpoint));
  }

  /** Injectable for tests — the SDK client is an interface. */
  ApiGatewayConnectionPusher(ApiGatewayManagementApiClient client) {
    this.client = client;
  }

  private static ApiGatewayManagementApiClient buildClient(
      String region, String managementEndpoint) {
    if (managementEndpoint == null || managementEndpoint.isBlank()) {
      throw new IllegalStateException(
          "WS_MANAGEMENT_ENDPOINT is required when WS_PROVIDER=apigateway");
    }
    if (managementEndpoint.startsWith("ws://") || managementEndpoint.startsWith("wss://")) {
      throw new IllegalStateException(
          "WS_MANAGEMENT_ENDPOINT must be the https:// management endpoint "
              + "(https://{api-id}.execute-api.{region}.amazonaws.com/{stage}), not the "
              + "wss:// URL clients connect to — got: "
              + managementEndpoint);
    }
    return ApiGatewayManagementApiClient.builder()
        .region(Region.of(region))
        .endpointOverride(URI.create(managementEndpoint))
        .build();
  }

  @Override
  public boolean push(String connectionId, String jsonPayload) {
    try {
      client.postToConnection(
          PostToConnectionRequest.builder()
              .connectionId(connectionId)
              .data(SdkBytes.fromUtf8String(jsonPayload))
              .build());
      return true;
    } catch (GoneException e) {
      // The client vanished without a clean $disconnect. Expected, not an error:
      // return false so the fan-out skips it and carries on to other devices.
      // The stale row in the presence table is Presence's to clean up (it owns
      // that table, and it has a TTL) — messaging must not write it.
      return false;
    } catch (RuntimeException e) {
      // PayloadTooLargeException (>128 KB), LimitExceededException (throttling),
      // ForbiddenException (wrong endpoint or missing ManageConnections), or any
      // transport failure. One dead connection must never fail the whole fan-out.
      System.err.println(
          "[messaging] push failed for " + connectionId + ": " + e.getClass().getSimpleName()
              + ": " + e.getMessage());
      return false;
    }
  }
}
