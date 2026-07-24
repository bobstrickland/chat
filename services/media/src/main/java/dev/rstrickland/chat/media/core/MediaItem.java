package dev.rstrickland.chat.media.core;

/**
 * Metadata for one uploaded media object (media-metadata table, PK mediaId).
 * The bytes live in S3/MinIO under `key`; a generated thumbnail (images only)
 * lives under `thumbnailKey`.
 *
 * @param status "pending" (upload URL issued) | "ready" (uploaded + processed)
 */
public record MediaItem(
    String mediaId,
    String ownerId,
    String key,
    String thumbnailKey,
    String contentType,
    String status,
    long size,
    String createdAt) {}
