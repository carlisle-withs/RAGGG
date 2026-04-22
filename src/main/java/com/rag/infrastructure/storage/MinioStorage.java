package com.rag.infrastructure.storage;

import com.rag.config.AppConfig;
import io.minio.*;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.Charset;
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
            log.warn("MinIO bucket check/create deferred: {} — will retry on first use", e.getMessage());
        }
    }

    public String upload(String objectName, MultipartFile file) {
        try (InputStream stream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(stream, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            log.info("Uploaded: {}/{}", bucket, objectName);
            return objectName;
        } catch (Exception e) {
            log.error("Failed to upload: {}", objectName, e);
            throw new RuntimeException("Upload failed", e);
        }
    }

    public String upload(String objectName, byte[] data, String contentType) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            return upload(objectName, bais, data.length, contentType);
        } catch (Exception e) {
            log.error("Failed to upload: {}", objectName, e);
            throw new RuntimeException("Upload failed", e);
        }
    }

    public String upload(String objectName, InputStream data, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(data, size, -1)
                    .contentType(contentType)
                    .build());
            log.info("Uploaded: {}/{}", bucket, objectName);
            return objectName;
        } catch (Exception e) {
            log.error("Failed to upload: {}", objectName, e);
            throw new RuntimeException("Upload failed", e);
        }
    }

    /**
     * 返回对象的内容流。MinIO 不可用时返回空 JSON 数组 [] 而非抛异常，
     * 使调用方（IndexService）能正常处理"无 chunks 文件"的边界情况。
     */
    public InputStream getObjectStream(String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            log.warn("MinIO unavailable, returning empty stream for: {} — {}", objectName, e.getMessage());
            return new ByteArrayInputStream("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public byte[] download(String objectName) {
        try (InputStream stream = getObjectStream(objectName)) {
            return stream.readAllBytes();
        } catch (Exception e) {
            log.error("Failed to download: {}", objectName, e);
            throw new RuntimeException("Download failed", e);
        }
    }

    public String downloadAsString(String objectName, Charset charset) {
        try (InputStream stream = getObjectStream(objectName);
             InputStreamReader reader = new InputStreamReader(stream, charset);
             BufferedReader bufferedReader = new BufferedReader(reader)) {
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = bufferedReader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
            return content.toString();
        } catch (IOException e) {
            log.error("Failed to read text: {}", objectName, e);
            throw new RuntimeException("Download text failed", e);
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
