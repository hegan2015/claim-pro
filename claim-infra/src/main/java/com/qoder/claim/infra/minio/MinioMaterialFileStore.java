package com.qoder.claim.infra.minio;

import com.qoder.claim.common.port.MaterialFileStore;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** 生产形态：材料文件存 MinIO 证据桶（materials/ 前缀，与证据链快照同桶不同键空间）。 */
public class MinioMaterialFileStore implements MaterialFileStore {

    private final MinioClient client;
    private final String bucket;

    public MinioMaterialFileStore(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("材料文件写入 MinIO 失败: " + objectKey, e);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("材料文件读取失败: " + objectKey, e);
        }
    }
}