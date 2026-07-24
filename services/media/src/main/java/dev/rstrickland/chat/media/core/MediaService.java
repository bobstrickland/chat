package dev.rstrickland.chat.media.core;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Media logic — pure. The flow is ASYNC:
 *   1. createUpload      → presigned PUT URL + a "pending" metadata row
 *   2. requestProcessing → (client PUT the bytes) mark "processing", publish
 *                          media.uploaded, return immediately
 *   3. process           → the WORKER (media.uploaded consumer): shrink,
 *                          overwrite the object, thumbnail, mark "ready"/"failed"
 *   4. getMedia          → presigned GET URLs (client polls until status=ready)
 *
 * Keeping the transcode off the request thread (step 3, not step 2) is the whole
 * point — a big video no longer blocks the /complete call. In AWS the worker is
 * an MSK/S3-triggered Lambda over the same process() core.
 */
public final class MediaService {

  private static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
  private static final Duration DOWNLOAD_TTL = Duration.ofHours(1);

  private final MediaRepository repository;
  private final Storage storage;
  private final MediaProcessor processor;
  private final MediaEventPublisher publisher;

  public MediaService(
      MediaRepository repository, Storage storage, MediaProcessor processor,
      MediaEventPublisher publisher) {
    this.repository = repository;
    this.storage = storage;
    this.processor = processor;
    this.publisher = publisher;
  }

  public record Upload(String mediaId, String uploadUrl, String key) {}

  public record MediaView(
      String mediaId, String contentType, String status, String url, String thumbnailUrl) {}

  public Upload createUpload(String ownerId, String contentType) {
    if (contentType == null || contentType.isBlank()) {
      throw new IllegalArgumentException("contentType is required");
    }
    String mediaId = UUID.randomUUID().toString();
    String key = "originals/" + mediaId;
    repository.put(
        new MediaItem(mediaId, ownerId, key, null, contentType, "pending", 0, Instant.now().toString()));
    String uploadUrl = storage.presignPut(key, contentType, UPLOAD_TTL);
    return new Upload(mediaId, uploadUrl, key);
  }

  /**
   * Called after the client has PUT the bytes. Marks the item "processing" and
   * publishes media.uploaded — returns immediately, WITHOUT running ffmpeg. Only
   * the owner may trigger this. The client polls getMedia until status=="ready".
   */
  public MediaView requestProcessing(String userId, String mediaId) {
    MediaItem item = requireOwned(userId, mediaId);
    MediaItem processing =
        new MediaItem(
            item.mediaId(), item.ownerId(), item.key(), item.thumbnailKey(), item.contentType(),
            "processing", item.size(), item.createdAt());
    repository.put(processing);
    publisher.mediaUploaded(mediaId);
    return view(processing);
  }

  /**
   * The WORKER: consume media.uploaded → shrink (image/video ≤1024, audio ≤128
   * kbps), overwrite the stored object with the shrunk version (+ correct
   * Content-Type), store a thumbnail if produced, mark "ready". No owner check
   * (internal, event-triggered).
   *
   * Idempotent: a redelivered event whose item is already "ready" is skipped —
   * important so a retry doesn't re-transcode (and re-shrink an already-shrunk
   * object). On an unexpected error the item is marked "failed" so the client
   * stops polling.
   */
  public void process(String mediaId) {
    MediaItem item = repository.get(mediaId);
    if (item == null || "ready".equals(item.status()) || "failed".equals(item.status())) {
      return; // gone, or already terminal — nothing to do
    }
    try {
      byte[] raw = storage.get(item.key());
      MediaProcessor.Processed p = processor.process(raw, item.contentType());

      // Overwrite the stored object with the shrunk bytes + the (possibly
      // changed) content type. put() sets Content-Type on the object.
      storage.put(item.key(), p.content(), p.contentType());

      String thumbnailKey = item.thumbnailKey();
      if (p.thumbnail() != null) {
        thumbnailKey = "thumbs/" + mediaId;
        storage.put(thumbnailKey, p.thumbnail(), "image/jpeg");
      }

      repository.put(
          new MediaItem(
              item.mediaId(), item.ownerId(), item.key(), thumbnailKey, p.contentType(),
              "ready", p.content().length, item.createdAt()));
    } catch (Exception e) {
      System.err.println("[media] processing failed for " + mediaId + ": " + e.getMessage());
      repository.put(
          new MediaItem(
              item.mediaId(), item.ownerId(), item.key(), item.thumbnailKey(), item.contentType(),
              "failed", item.size(), item.createdAt()));
    }
  }

  /**
   * Any authenticated user who has the mediaId may fetch its URLs — the id is
   * an unguessable UUID shared inside a conversation (the capability). A stricter
   * "is the requester a member of a conversation containing this media" check is
   * cross-service and deferred.
   */
  public MediaView getMedia(String userId, String mediaId) {
    MediaItem item = repository.get(mediaId);
    if (item == null) {
      throw new NotFoundException("media not found");
    }
    return view(item);
  }

  private MediaView view(MediaItem item) {
    String url = storage.presignGet(item.key(), DOWNLOAD_TTL);
    String thumbUrl =
        item.thumbnailKey() == null ? null : storage.presignGet(item.thumbnailKey(), DOWNLOAD_TTL);
    return new MediaView(item.mediaId(), item.contentType(), item.status(), url, thumbUrl);
  }

  private MediaItem requireOwned(String userId, String mediaId) {
    MediaItem item = repository.get(mediaId);
    if (item == null) {
      throw new NotFoundException("media not found");
    }
    if (!item.ownerId().equals(userId)) {
      throw new ForbiddenException("not your upload");
    }
    return item;
  }

  public static final class NotFoundException extends RuntimeException {
    public NotFoundException(String m) {
      super(m);
    }
  }

  public static final class ForbiddenException extends RuntimeException {
    public ForbiddenException(String m) {
      super(m);
    }
  }
}
