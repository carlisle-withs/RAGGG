package com.rag.infrastructure.storage;

import com.rag.config.AppConfig;
import io.minio.*;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Component
public class MinioStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioStorage.class);

    private final MinioClient minioClient;
    private final String bucket;

    public MinioStorage(AppConfig appConfig) {
        this.bucket = appConfig.getMinio().getBucket();
        this.minioClient = MinioClient.builder()
                .endpoint(appConfig.getMinio().getEndpoint())
                .credentials(appConfig.getMinio().getAccessKey(), appConfig.getMinio().getSecretKey())
                .build();
    }

    @PostConstruct
    public void init() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to initialize MinIO bucket", e);
        }
    }

    public String upload(String objectName, byte[] data, String contentType) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(bais, data.length, -1)
                    .contentType(contentType)
                    .build());
            log.info("Uploaded: {}/{}", bucket, objectName);
            return objectName;
        } catch (Exception e) {
            log.error("Failed to upload: {}", objectName, e);
            throw new RuntimeException("Upload failed", e);
        }
    }

    public byte[] download(String objectName) {
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            log.error("Failed to download: {}", objectName, e);
            throw new RuntimeException("Download failed", e);
        }
    }

    public String getUrl(String objectName, int expiryMinutes) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(expiryMinutes, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            log.error("Failed to generate URL: {}", objectName, e);
            throw new RuntimeException("URL generation failed", e);
        }
    }

    public void delete(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
            log.info("Deleted: {}/{}", bucket, objectName);
        } catch (Exception e) {
            log.error("Failed to delete: {}", objectName, e);
            throw new RuntimeException("Delete failed", e);
        }
    }
}
