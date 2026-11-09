package com.share.ops.file.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** MinIO 对象存储适配：文件落本地后同步上传，下载时可按 objectKey 回读。 */
@Slf4j
@Service
public class MinioStorageService {

    @Value("${share.storage.minio.enabled:false}")
    private boolean enabled;

    @Value("${share.storage.minio.endpoint:http://127.0.0.1:9000}")
    private String endpoint;

    @Value("${share.storage.minio.access-key:shareadmin}")
    private String accessKey;

    @Value("${share.storage.minio.secret-key:shareadmin123}")
    private String secretKey;

    @Value("${share.storage.minio.bucket:share-files}")
    private String bucket;

    private volatile MinioClient client;
    private volatile boolean ready;
    private volatile String lastError;

    @PostConstruct
    public void init() {
        if (!enabled) {
            return;
        }
        try {
            client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            ready = true;
            log.info("MinIO 对象存储已启用 endpoint={} bucket={}", endpoint, bucket);
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("MinIO 初始化失败，回退本地存储：{}", e.getMessage());
        }
    }

    public boolean available() {
        return enabled && ready && client != null;
    }

    public boolean enabled() {
        return enabled;
    }

    public String upload(Path file, String objectKey) {
        if (!available()) {
            return null;
        }
        try {
            long size = Files.size(file);
            try (InputStream in = Files.newInputStream(file)) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(in, size, -1)
                        .contentType("application/octet-stream")
                        .build());
            }
            return objectKey;
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("MinIO 上传失败 key={}: {}", objectKey, e.getMessage());
            return null;
        }
    }

    public InputStream open(String objectKey) {
        if (!available() || objectKey == null || objectKey.isBlank()) {
            return null;
        }
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("MinIO 下载失败 key={}: {}", objectKey, e.getMessage());
            return null;
        }
    }

    public void delete(String objectKey) {
        if (!available() || objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.warn("MinIO 删除失败 key={}: {}", objectKey, e.getMessage());
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("available", available());
        result.put("endpoint", enabled ? endpoint : null);
        result.put("bucket", enabled ? bucket : null);
        result.put("lastError", lastError);
        return result;
    }
}
