package dev.rstrickland.chat.net.api;

import dev.rstrickland.chat.model.MediaModels;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Media service (port 3006). Bearer added by the interceptor. The actual bytes
 * are PUT/GET directly against MinIO via presigned URLs (see MediaBlobClient) —
 * these calls only broker those URLs and read processing status.
 */
public interface MediaApi {

    @POST("media/uploads")
    Call<MediaModels.CreateUploadResponse> createUpload(@Body MediaModels.CreateUploadRequest body);

    /** Enqueue processing (shrink/thumbnail). Returns 202 with status "processing". */
    @POST("media/{mediaId}/complete")
    Call<MediaModels.MediaView> complete(@Path("mediaId") String mediaId);

    @GET("media/{mediaId}")
    Call<MediaModels.MediaView> get(@Path("mediaId") String mediaId);
}
