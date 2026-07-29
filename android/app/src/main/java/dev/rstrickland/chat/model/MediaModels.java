package dev.rstrickland.chat.model;

/** Media service shapes (Phase 8/8.5). Three-step upload + async processing. */
public final class MediaModels {
    private MediaModels() {}

    /** POST /media/uploads body. */
    public static final class CreateUploadRequest {
        public String contentType;

        public CreateUploadRequest(String contentType) {
            this.contentType = contentType;
        }
    }

    /** POST /media/uploads response: a mediaId + a presigned PUT URL. */
    public static final class CreateUploadResponse {
        public String mediaId;
        public String uploadUrl;
    }

    /**
     * The media view returned by GET /media/{id} and /complete. Processing is
     * async, so {@code status} walks pending → processing → ready|failed; the
     * presigned {@code url}/{@code thumbnailUrl} are usable once ready.
     */
    public static final class MediaView {
        public String mediaId;
        public String contentType;
        public String status;       // pending | processing | ready | failed
        public String url;          // presigned GET (original/shrunk)
        public String thumbnailUrl; // presigned GET (image/video thumbnail), may be null

        public boolean isReady() {
            return "ready".equals(status);
        }

        public boolean isFailed() {
            return "failed".equals(status);
        }

        /** Inline URL — the thumbnail if present (smaller), else the full object. */
        public String displayUrl() {
            return thumbnailUrl != null ? thumbnailUrl : url;
        }
    }
}
