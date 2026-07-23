package dev.rstrickland.chat.presence.adapters;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import dev.rstrickland.chat.presence.Config;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS-side adapter: the API Gateway WebSocket integration invokes this with the
 * $connect/$disconnect/$default route events. It calls the SAME WebSocketRouter
 * as HttpServerMain, so the presence semantics are identical whether the service
 * runs on Lambda or Fargate — that's the point of the core/adapters split.
 *
 * Per CLAUDE.md: no reliance on Lambda execution-context reuse for correctness.
 * The static Config is a warm-start perf bonus; a cold start rebuilds it and
 * still works.
 *
 * Not exercised locally (HttpServerMain is). The HTTP query endpoints
 * (/presence/status, /internal/.../connections) would be wired to their own
 * HTTP-API Lambda route when this is deployed for real (Phase 10); kept out of
 * here to avoid an unexercised second dispatch path.
 */
public final class LambdaHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

  private static volatile Config config;

  private static Config config() {
    Config c = config;
    if (c == null) {
      synchronized (LambdaHandler.class) {
        if (config == null) {
          config = Config.fromEnv();
        }
        c = config;
      }
    }
    return c;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
    Map<String, Object> ctx = (Map<String, Object>) event.getOrDefault("requestContext", Map.of());
    String routeKey = str(ctx.get("routeKey"));
    String connectionId = str(ctx.get("connectionId"));

    Map<String, String> query = new HashMap<>();
    Object qsp = event.get("queryStringParameters");
    if (qsp instanceof Map<?, ?> m) {
      m.forEach((k, v) -> query.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
    }

    int status = config().webSocketRouter.dispatch(routeKey, connectionId, query);
    return Map.of("statusCode", status);
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }
}
