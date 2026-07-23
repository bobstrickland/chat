package dev.rstrickland.chat.messaging.core;

/**
 * Pushes a payload to one WebSocket connection. Implemented against ws-shim's
 * postToConnection locally / ApiGatewayManagementApi in AWS.
 */
public interface ConnectionPusher {
  /**
   * @return true if delivered; false if the connection was gone (stale). A
   *     stale connection is normal — the client dropped without a clean
   *     $disconnect — and must not fail the whole fan-out.
   */
  boolean push(String connectionId, String jsonPayload);
}
