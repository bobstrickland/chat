# Media Service

Upload, thumbnail, and serve media (images) for chat messages. Owns the
`media-metadata` table and the media S3 bucket. Runs on `:3006`.

## Language: Java

Media has no default language (CLAUDE.md "Language / Runtime"). **Java, for
imaging:** thumbnailing via **Thumbnailator/ImageIO is pure JVM** — no native
dependency (Node's `sharp` needs libvips). The transform is async (not on a
user-latency path), so JVM cold start doesn't matter here. Reuses the Java
service conventions (no Spring, JDK HttpServer, AWS SDK v2, shade fat jar).

## Flow

```
1. POST /media/uploads { contentType }        -> { mediaId, uploadUrl }   (presigned PUT + pending row)
2. client PUTs the file directly to uploadUrl (straight to storage, not this API)
3. POST /media/{mediaId}/complete             -> fetch bytes, thumbnail if image, mark "ready"
4. GET  /media/{mediaId}                       -> { url, thumbnailUrl, contentType, status }  (presigned GETs)
```

The transform in step 3 is what an S3-object-created **Lambda** would do in AWS;
run inline here (local-first) — same `MediaService.completeUpload` core.

## The MinIO presign split (important)

MinIO is `minio:9000` inside the compose network but `localhost:9000` to the
browser, and SigV4 signs the host. So `S3Storage` holds **two** clients:

- an internal `S3Client` (`S3_ENDPOINT` = minio:9000) for server-side get/put, and
- an `S3Presigner` bound to the **public** endpoint
  (`MEDIA_S3_PUBLIC_ENDPOINT` = http://localhost:9000) for the URLs the browser uses.

Presigned PUT signs no content-type, so the browser's own `Content-Type` header
applies without a signature mismatch. Browser uploads need MinIO CORS
(`MINIO_API_CORS_ALLOW_ORIGIN` in the compose minio service); inline `<img>`
loads don't (images are CORS-exempt).

## Authorization

Only the owner may `complete` an upload. Anyone authenticated who has a mediaId
may fetch its URLs — the id is an unguessable UUID shared inside a conversation
(the capability). A stricter "requester is a member of a conversation containing
this media" check is cross-service and deferred.

## Setup / run

The bucket is not auto-created (like the DynamoDB tables aren't):

```
AWS_ACCESS_KEY_ID=localminio AWS_SECRET_ACCESS_KEY=localminio123 \
  aws s3 mb s3://media-local --endpoint-url http://localhost:9000
```

```
mvn package                                    # fat jar + 7 core tests
docker compose up -d --build media-service     # :3006
```

Config (`.env`): `MEDIA_METADATA_TABLE`, `S3_ENDPOINT`, `MEDIA_S3_PUBLIC_ENDPOINT`,
`MEDIA_BUCKET`, `AWS_ACCESS_KEY_ID_S3` / `AWS_SECRET_ACCESS_KEY_S3`,
`COGNITO_JWKS_URL`, `DYNAMODB_ENDPOINT` (local), `AWS_REGION`, `PORT`.
