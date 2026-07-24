package dev.rstrickland.chat.media.core;

/**
 * Announces that a raw upload is ready to be processed (topic media.uploaded).
 * Implemented by clients/ against Kafka/MSK. This is what makes shrinking async:
 * the /complete request publishes and returns; a consumer does the transcode.
 */
public interface MediaEventPublisher {
  void mediaUploaded(String mediaId);
}
