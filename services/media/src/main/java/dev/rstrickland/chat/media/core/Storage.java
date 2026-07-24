package dev.rstrickland.chat.media.core;

import java.time.Duration;

/**
 * Object storage boundary (S3 in AWS, MinIO locally). Implemented by clients/.
 *
 * Presigned URLs are generated against the PUBLIC endpoint (what the browser can
 * reach — localhost:9000 in dev), while get/put run server-side against the
 * INTERNAL endpoint (minio:9000). Same objects, two network paths — that split
 * lives in the implementation.
 */
public interface Storage {
  /** A time-limited URL the client PUTs the file to directly. */
  String presignPut(String key, String contentType, Duration ttl);

  /** A time-limited URL the client GETs the file from directly (e.g. <img src>). */
  String presignGet(String key, Duration ttl);

  byte[] get(String key);

  /** Store bytes with a Content-Type (used to overwrite the raw upload with the shrunk form). */
  void put(String key, byte[] bytes, String contentType);
}
