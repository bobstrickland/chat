package dev.rstrickland.chat.media.clients;

import dev.rstrickland.chat.media.core.Storage;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3/MinIO storage. Two clients on purpose:
 *   - `s3`        : INTERNAL endpoint (minio:9000) for server-side get/put (transforms).
 *   - `presigner` : PUBLIC endpoint (localhost:9000) for URLs the BROWSER uses.
 * SigV4 signs the host, so a URL presigned for the internal host would be
 * unreachable/invalid from the browser — hence the split.
 *
 * Presigned PUT deliberately does NOT sign a content-type: the browser sends its
 * own Content-Type header (which MinIO stores) without a signature-mismatch risk.
 */
public final class S3Storage implements Storage {

  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;

  public S3Storage(
      String internalEndpoint, String publicEndpoint, String region, String bucket,
      String accessKey, String secretKey) {
    this.bucket = bucket;
    var creds =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();

    this.s3 =
        S3Client.builder()
            .region(Region.of(region))
            .endpointOverride(URI.create(internalEndpoint))
            .serviceConfiguration(pathStyle)
            .credentialsProvider(creds)
            .build();
    this.presigner =
        S3Presigner.builder()
            .region(Region.of(region))
            .endpointOverride(URI.create(publicEndpoint))
            .serviceConfiguration(pathStyle)
            .credentialsProvider(creds)
            .build();
  }

  @Override
  public String presignPut(String key, String contentType, Duration ttl) {
    var req =
        PutObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
            .build();
    return presigner.presignPutObject(req).url().toString();
  }

  @Override
  public String presignGet(String key, Duration ttl) {
    var req =
        GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .build();
    return presigner.presignGetObject(req).url().toString();
  }

  @Override
  public byte[] get(String key) {
    return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
        .asByteArray();
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(bytes));
  }
}
