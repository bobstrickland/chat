package dev.rstrickland.chat.media.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** core tested with in-memory fakes — no S3, no DynamoDB, no image/ffmpeg. */
class MediaServiceTest {

  static final class FakeRepo implements MediaRepository {
    final Map<String, MediaItem> items = new HashMap<>();

    public void put(MediaItem item) {
      items.put(item.mediaId(), item);
    }

    public MediaItem get(String mediaId) {
      return items.get(mediaId);
    }
  }

  static final class FakeStorage implements Storage {
    final Map<String, byte[]> objects = new HashMap<>();
    final Map<String, String> contentTypes = new HashMap<>();

    public String presignPut(String key, String contentType, Duration ttl) {
      return "https://public/put/" + key;
    }

    public String presignGet(String key, Duration ttl) {
      return "https://public/get/" + key;
    }

    public byte[] get(String key) {
      return objects.getOrDefault(key, new byte[0]);
    }

    public void put(String key, byte[] bytes, String contentType) {
      objects.put(key, bytes);
      contentTypes.put(key, contentType);
    }
  }

  static final class FakePublisher implements MediaEventPublisher {
    final List<String> published = new ArrayList<>();

    public void mediaUploaded(String mediaId) {
      published.add(mediaId);
    }
  }

  /** Counts how many times it actually processes, to check idempotency. */
  static final class CountingProcessor implements MediaProcessor {
    final byte[] content;
    final String contentType;
    final byte[] thumbnail;
    int calls = 0;

    CountingProcessor(byte[] content, String contentType, byte[] thumbnail) {
      this.content = content;
      this.contentType = contentType;
      this.thumbnail = thumbnail;
    }

    public Processed process(byte[] original, String ct) {
      calls++;
      return new Processed(content, contentType, thumbnail);
    }
  }

  static MediaProcessor processor(byte[] content, String contentType, byte[] thumbnail) {
    return (original, ct) -> new MediaProcessor.Processed(content, contentType, thumbnail);
  }

  /** A passthrough processor (no shrink, no thumbnail) preserving the input. */
  static final MediaProcessor PASSTHROUGH = (original, ct) -> new MediaProcessor.Processed(original, ct, null);

  MediaService svc(FakeRepo repo, FakeStorage storage, MediaProcessor p) {
    return new MediaService(repo, storage, p, new FakePublisher());
  }

  MediaService svc(FakeRepo repo, FakeStorage storage, MediaProcessor p, MediaEventPublisher pub) {
    return new MediaService(repo, storage, p, pub);
  }

  @Test
  void createUploadIssuesUrlAndPendingRow() {
    FakeRepo repo = new FakeRepo();
    MediaService.Upload up = svc(repo, new FakeStorage(), PASSTHROUGH).createUpload("alice", "image/png");
    assertNotNull(up.uploadUrl());
    assertEquals("pending", repo.get(up.mediaId()).status());
    assertEquals("alice", repo.get(up.mediaId()).ownerId());
  }

  @Test
  void createUploadRequiresContentType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> svc(new FakeRepo(), new FakeStorage(), PASSTHROUGH).createUpload("alice", " "));
  }

  @Test
  void requestProcessingMarksProcessingAndPublishesWithoutTranscoding() {
    FakeRepo repo = new FakeRepo();
    FakeStorage storage = new FakeStorage();
    CountingProcessor proc = new CountingProcessor(new byte[] {1}, "image/png", new byte[] {2});
    FakePublisher pub = new FakePublisher();
    MediaService svc = svc(repo, storage, proc, pub);
    MediaService.Upload up = svc.createUpload("alice", "image/png");

    MediaService.MediaView view = svc.requestProcessing("alice", up.mediaId());
    assertEquals("processing", view.status(), "returns immediately, not ready");
    assertEquals(List.of(up.mediaId()), pub.published, "enqueued for the worker");
    assertEquals(0, proc.calls, "the transcode did NOT run on the request thread");
  }

  @Test
  void processStoresShrunkBytesThumbnailAndMarksReady() {
    FakeRepo repo = new FakeRepo();
    FakeStorage storage = new FakeStorage();
    byte[] shrunk = {1, 2};
    byte[] thumb = {9, 9, 9};
    MediaService svc = svc(repo, storage, processor(shrunk, "image/png", thumb));
    MediaService.Upload up = svc.createUpload("alice", "image/png");
    storage.objects.put(up.key(), new byte[] {5, 5, 5, 5, 5}); // raw upload
    svc.requestProcessing("alice", up.mediaId());

    svc.process(up.mediaId()); // the worker

    assertEquals("ready", repo.get(up.mediaId()).status());
    assertArrayEquals(shrunk, storage.objects.get(up.key()), "object overwritten with shrunk bytes");
    assertArrayEquals(thumb, storage.objects.get("thumbs/" + up.mediaId()));
    assertEquals(2, repo.get(up.mediaId()).size());
  }

  @Test
  void processCanChangeContentType() {
    FakeRepo repo = new FakeRepo();
    FakeStorage storage = new FakeStorage();
    MediaService svc = svc(repo, storage, processor(new byte[] {1}, "video/mp4", new byte[] {2}));
    MediaService.Upload up = svc.createUpload("alice", "video/webm");
    storage.objects.put(up.key(), new byte[] {0, 0});
    svc.requestProcessing("alice", up.mediaId());

    svc.process(up.mediaId());

    assertEquals("video/mp4", repo.get(up.mediaId()).contentType(), "output content type stored");
    assertEquals("video/mp4", storage.contentTypes.get(up.key()));
  }

  @Test
  void processIsIdempotentForAlreadyReadyItems() {
    FakeRepo repo = new FakeRepo();
    FakeStorage storage = new FakeStorage();
    CountingProcessor proc = new CountingProcessor(new byte[] {1}, "image/png", null);
    MediaService svc = svc(repo, storage, proc);
    MediaService.Upload up = svc.createUpload("alice", "image/png");
    storage.objects.put(up.key(), new byte[] {5});
    svc.requestProcessing("alice", up.mediaId());

    svc.process(up.mediaId()); // first: processes
    svc.process(up.mediaId()); // redelivery: must be a no-op
    assertEquals(1, proc.calls, "already-ready item is not re-transcoded");
  }

  @Test
  void onlyTheOwnerMayRequestProcessing() {
    FakeRepo repo = new FakeRepo();
    FakeStorage storage = new FakeStorage();
    MediaService svc = svc(repo, storage, PASSTHROUGH);
    MediaService.Upload up = svc.createUpload("alice", "image/png");
    storage.objects.put(up.key(), new byte[] {1});
    assertThrows(
        MediaService.ForbiddenException.class, () -> svc.requestProcessing("mallory", up.mediaId()));
  }

  @Test
  void getMediaOfUnknownIdIsNotFound() {
    assertThrows(
        MediaService.NotFoundException.class,
        () -> svc(new FakeRepo(), new FakeStorage(), PASSTHROUGH).getMedia("alice", "nope"));
  }
}
