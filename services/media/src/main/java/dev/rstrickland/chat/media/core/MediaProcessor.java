package dev.rstrickland.chat.media.core;

/**
 * Shrinks and thumbnails media before it's stored (Phase 8.5). Implemented by
 * clients/ (ImageIO for images, ffmpeg for video/audio). Kept behind this
 * interface so core stays free of imaging/ffmpeg specifics.
 *
 * Contract: never throws for a weird/undecodable input — on failure it returns
 * the original bytes unchanged (with no thumbnail), so an upload still succeeds.
 */
public interface MediaProcessor {

  /**
   * @param content     the media to store (shrunk if it could be; original otherwise)
   * @param contentType the OUTPUT content type — may differ from the input (e.g. a
   *                    webm video transcoded to mp4, or wav audio to mp3)
   * @param thumbnail   a JPEG thumbnail (image poster / video frame), or null
   */
  record Processed(byte[] content, String contentType, byte[] thumbnail) {}

  Processed process(byte[] original, String contentType);
}
