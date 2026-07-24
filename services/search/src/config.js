import { createOpenSearchClient } from "./clients/openSearchClient.js";
import { createMessagingClient } from "./clients/messagingClient.js";
import { createTokenVerifier } from "./clients/jwksVerifier.js";

/** Dependency bundle every core/ function receives — the config.js pattern. */
export function getDependencies() {
  return {
    openSearch: createOpenSearchClient({
      node: process.env.OPENSEARCH_ENDPOINT,
      messagesIndex: process.env.SEARCH_MESSAGES_INDEX ?? "messages",
      profilesIndex: process.env.SEARCH_PROFILES_INDEX ?? "profiles",
    }),
    messagingClient: createMessagingClient({
      baseUrl: process.env.MESSAGING_SERVICE_URL ?? "http://messaging-service:3000",
    }),
    verifyToken: createTokenVerifier(process.env.COGNITO_JWKS_URL),
  };
}

/** message.sent → message docs. Its own group so it sees every message. */
export function getMessageConsumerConfig() {
  return {
    brokers: process.env.KAFKA_BROKERS,
    topic: process.env.TOPIC_MESSAGE_SENT ?? "message.sent",
    groupId: process.env.SEARCH_MESSAGE_GROUP ?? "search-messages",
  };
}

/** search.index → profile docs (generic indexing envelope from Profile). */
export function getIndexConsumerConfig() {
  return {
    brokers: process.env.KAFKA_BROKERS,
    topic: process.env.TOPIC_SEARCH_INDEX ?? "search.index",
    groupId: process.env.SEARCH_INDEX_GROUP ?? "search-index",
  };
}
