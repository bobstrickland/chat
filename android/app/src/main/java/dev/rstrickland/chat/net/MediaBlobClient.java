package dev.rstrickland.chat.net;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Transfers media BYTES directly to/from MinIO via presigned URLs — deliberately
 * separate from the Retrofit API client:
 *   - it carries NO Authorization header (presigned URLs authenticate via their
 *     query signature; an extra bearer would confuse MinIO), and
 *   - it uses {@link DevMediaDns} so the emulator can actually reach the
 *     localhost-signed URLs.
 *
 * All calls are blocking — invoke them off the main thread.
 */
public final class MediaBlobClient {

    private static MediaBlobClient instance;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .dns(new DevMediaDns())
            .build();

    private MediaBlobClient() {}

    public static synchronized MediaBlobClient get() {
        if (instance == null) instance = new MediaBlobClient();
        return instance;
    }

    /** Upload bytes to a presigned PUT URL. The presigned PUT signs no content-type. */
    public void put(String uploadUrl, byte[] bytes, String contentType) throws IOException {
        MediaType type = MediaType.parse(contentType != null ? contentType : "application/octet-stream");
        Request request = new Request.Builder()
                .url(uploadUrl)
                .put(RequestBody.create(bytes, type))
                .build();
        try (Response res = http.newCall(request).execute()) {
            if (!res.isSuccessful()) {
                throw new IOException("upload PUT failed: " + res.code());
            }
        }
    }

    /** Fetch and decode an image from a presigned GET URL. */
    public Bitmap getBitmap(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response res = http.newCall(request).execute()) {
            if (!res.isSuccessful()) {
                throw new IOException("media GET failed: " + res.code());
            }
            ResponseBody body = res.body();
            if (body == null) throw new IOException("empty media body");
            try (InputStream in = body.byteStream()) {
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp == null) throw new IOException("could not decode image");
                return bmp;
            }
        }
    }
}
