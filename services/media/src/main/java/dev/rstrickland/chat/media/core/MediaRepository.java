package dev.rstrickland.chat.media.core;

public interface MediaRepository {
  void put(MediaItem item);

  /** null if not found. */
  MediaItem get(String mediaId);
}
