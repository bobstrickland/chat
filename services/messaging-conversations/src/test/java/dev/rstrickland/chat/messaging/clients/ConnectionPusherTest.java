package dev.rstrickland.chat.messaging.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PayloadTooLargeException;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionResponse;

/**
 * Covers provider SELECTION and the AWS pusher's error mapping — the two places
 * this swap can go wrong. The happy path against real API Gateway can't be
 * tested here (it needs a live WS API), but the contract that matters to
 * DeliveryService / ReceiptBroadcaster / DeletionBroadcaster is just
 * "true = delivered, false = skip, never throw", and that is fully testable.
 */
class ConnectionPusherTest {

  // --- selection -----------------------------------------------------------

  @Test
  void defaultsToTheLocalShim() {
    for (String mode : new String[] {null, "", "  ", "shim", "SHIM"}) {
      var pusher =
          ConnectionPushers.create(mode, "http://ws-shim:8090", "/@connections", "us-east-1", null);
      assertInstanceOf(WsShimConnectionPusher.class, pusher, "mode=" + mode);
    }
  }

  @Test
  void apigatewaySelectsTheAwsPusher() {
    var pusher =
        ConnectionPushers.create(
            "apigateway",
            null,
            null,
            "us-east-1",
            "https://abc123.execute-api.us-east-1.amazonaws.com/dev");
    assertInstanceOf(ApiGatewayConnectionPusher.class, pusher);
  }

  @Test
  void anUnknownProviderFailsFastRatherThanSilentlyUsingTheShim() {
    // A typo must not leave AWS pushing at a host that doesn't exist — pushes
    // fail silently by design, so this has to surface at startup.
    var e =
        assertThrows(
            IllegalStateException.class,
            () -> ConnectionPushers.create("api-gateway", null, null, "us-east-1", "https://x/dev"));
    assertTrue(e.getMessage().contains("WS_PROVIDER"), e.getMessage());
  }

  @Test
  void eachProviderValidatesItsOwnRequiredEndpoint() {
    var shim =
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionPushers.create("shim", null, "/@connections", "us-east-1", null));
    assertTrue(shim.getMessage().contains("WS_SHIM_ENDPOINT"), shim.getMessage());

    var api =
        assertThrows(
            IllegalStateException.class,
            () -> ConnectionPushers.create("apigateway", null, null, "us-east-1", null));
    assertTrue(api.getMessage().contains("WS_MANAGEMENT_ENDPOINT"), api.getMessage());
  }

  @Test
  void theWssUrlIsRejectedWithAnActionableMessage() {
    // Passing the client-facing wss:// URL instead of the https:// management
    // endpoint otherwise yields opaque 403s at push time.
    var e =
        assertThrows(
            IllegalStateException.class,
            () ->
                ConnectionPushers.create(
                    "apigateway",
                    null,
                    null,
                    "us-east-1",
                    "wss://abc123.execute-api.us-east-1.amazonaws.com/dev"));
    assertTrue(e.getMessage().contains("management endpoint"), e.getMessage());
  }

  // --- AWS pusher behaviour ------------------------------------------------

  /** Records requests; each call returns/throws whatever the script says. */
  static class FakeClient implements ApiGatewayManagementApiClient {
    final List<PostToConnectionRequest> sent = new ArrayList<>();
    RuntimeException toThrow;

    @Override
    public PostToConnectionResponse postToConnection(PostToConnectionRequest request) {
      sent.add(request);
      if (toThrow != null) {
        throw toThrow;
      }
      return PostToConnectionResponse.builder().build();
    }

    @Override
    public String serviceName() {
      return "execute-api";
    }

    @Override
    public void close() {}
  }

  @Test
  void aSuccessfulPostSendsTheConnectionIdAndPayload() {
    var client = new FakeClient();
    var pusher = new ApiGatewayConnectionPusher(client);

    assertTrue(pusher.push("conn-1", "{\"type\":\"message\"}"));
    assertEquals(1, client.sent.size());
    assertEquals("conn-1", client.sent.get(0).connectionId());
    assertEquals("{\"type\":\"message\"}", client.sent.get(0).data().asUtf8String());
  }

  @Test
  void goneMeansSkipThisConnection_notAnError() {
    var client = new FakeClient();
    client.toThrow = GoneException.builder().message("gone").build();
    var pusher = new ApiGatewayConnectionPusher(client);

    // false, and crucially no exception: the caller keeps fanning out to the
    // recipient's other devices.
    assertFalse(pusher.push("stale", "{}"));
  }

  @Test
  void anyOtherFailureIsSwallowedAsFalse() {
    var pusher0 = new ApiGatewayConnectionPusher(new FakeClient());
    assertTrue(pusher0.push("ok", "{}"), "sanity");

    for (RuntimeException failure :
        new RuntimeException[] {
          PayloadTooLargeException.builder().message("128 KB limit").build(),
          AwsServiceException.builder().message("throttled").build(),
          new IllegalStateException("socket blew up")
        }) {
      var client = new FakeClient();
      client.toThrow = failure;
      var pusher = new ApiGatewayConnectionPusher(client);
      assertFalse(
          pusher.push("conn", "{}"), failure.getClass().getSimpleName() + " must not propagate");
    }
  }
}
