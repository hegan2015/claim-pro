package com.qoder.claim.infra.minio;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** 生产形态：MinIO 对象存储（桶开 WORM 合规保留策略，保留≥10年）。 */
@Slf4j
public class MinioEvidenceStore extends AbstractEvidenceStore {

    private final MinioClient client;
    private final String bucket;

    public MinioEvidenceStore(JdbcTemplate jdbc, MinioClient client, String bucket) {
        super(jdbc);
        this.client = client;
        this.bucket = bucket;
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            log.error("MinIO 证据桶初始化失败（{}），证据写入将报错直至恢复", e.getMessage());
        }
    }

    @Override
    protected void putBytes(String objectKey, byte[] bytes) throws Exception {
        client.putObject(PutObjectArgs.builder()
                .bucket(bucket).object(objectKey)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .contentType("application/json")
                .build());
    }

    @Override
    protected byte[] getBytes(String objectKey) throws Exception {
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return in.readAllBytes();
        }
    }
}