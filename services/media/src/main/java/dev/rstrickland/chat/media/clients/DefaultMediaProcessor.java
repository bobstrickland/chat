package dev.rstrickland.chat.media.clients;

import dev.rstrickland.chat.media.core.MediaProcessor;

/**
 * Branches by content type:
 *   image/* → ImageIO shrink (<=1024) + JPEG thumbnail (pure JVM)
 *   video/* → ffmpeg shrink (<=1024, h264 mp4) + frame thumbnail
 *   audio/* → ffmpeg re-encode (<=128 kbps mp3)
 *   other   → passthrough (stored as-is, no thumbnail)
 *
 * Any shrink that fails falls back to the original bytes, so an odd file still
 * uploads (just un-shrunk). Video/audio outputs are normalized to mp4/mp3, so
 * the OUTPUT content type may differ from the input — the caller stores that.
 */
public final class DefaultMediaProcessor implements MediaProcessor {

  @Override
  public Processed process(byte[] original, String contentType) {
    if (contentType == null) {
      return new Processed(original, "application/octet-stream", null);
    }
    if (contentType.startsWith("image/")) {
      byte[] shrunk = ImageOps.shrink(original);
      byte[] thumb = ImageOps.thumbnail(original);
      return new Processed(shrunk != null ? shrunk : original, contentType, thumb);
    }
    if (contentType.startsWith("video/")) {
      byte[] shrunk = Ffmpeg.shrinkVideo(original);
      byte[] thumb = Ffmpeg.videoThumbnail(original);
      return shrunk != null
          ? new Processed(shrunk, "video/mp4", thumb)
          : new Processed(original, contentType, thumb);
    }
    if (contentType.startsWith("audio/")) {
      byte[] shrunk = Ffmpeg.shrinkAudio(original);
      return shrunk != null
          ? new Processed(shrunk, "audio/mpeg", null)
          : new Processed(original, contentType, null);
    }
    return new Processed(original, contentType, null);
  }
}
