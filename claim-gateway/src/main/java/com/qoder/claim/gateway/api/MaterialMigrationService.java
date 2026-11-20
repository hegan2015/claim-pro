package com.qoder.claim.gateway.api;

import com.qoder.claim.common.port.MaterialFileStore;
import com.qoder.claim.infra.minio.LocalMaterialFileStore;
import com.qoder.claim.infra.minio.MinioMaterialFileStore;
import com.qoder.claim.infra.minio.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 材料文件存储切换迁移：本地磁盘 ↔ MinIO 双向复制。
 * 逐文件读源→复核登记哈希→写目标→读回再复核；目标已存在且哈希一致则跳过（幂等可重跑）。
 * 切换 claim.minio.enabled 前先跑本迁移，避免旧模式文件成为孤儿（查看 503）。
 */
@Slf4j
@Service
public class MaterialMigrationService {

    private final MinioProperties props;
    private final JdbcTemplate jdbc;

    public MaterialMigrationService(MinioProperties props, JdbcTemplate jdbc) {
        this.props = props;
        this.jdbc = jdbc;
    }

    public Map<String, Object> migrate(String direction) throws Exception {
        boolean toMinio = "local-to-minio".equals(direction);
        if (!toMinio && !"minio-to-local".equals(direction)) {
            throw new IllegalArgumentException("direction 须为 local-to-minio 或 minio-to-local");
        }
        MaterialFileStore local = new LocalMaterialFileStore(props.getLocalDir());
        MaterialFileStore minio = minioStore();
        MaterialFileStore src = toMinio ? local : minio;
        MaterialFileStore dst = toMinio ? minio : local;

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT material_id, case_no, storage_key, sha256, content_type FROM claim_material WHERE size_bytes IS NOT NULL ORDER BY id");
        int copied = 0;
        int skipped = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String mid = (String) r.get("material_id");
            String key = (String) r.get("storage_key");
            String expectHash = (String) r.get("sha256");
            try {
                byte[] bytes = src.get(key);
                if (!sha256(bytes).equals(expectHash)) {
                    throw new IllegalStateException("源文件哈希与登记不一致（疑似篡改）");
                }
                if (existsWithHash(dst, key, expectHash)) {
                    skipped++;
                    continue;
                }
                String ct = (String) r.get("content_type");
                dst.put(key, bytes, ct == null ? "application/octet-stream" : ct);
                if (!sha256(dst.get(key)).equals(expectHash)) {
                    throw new IllegalStateException("目标读回复核失败");
                }
                copied++;
            } catch (Exception e) {
                failed.add(Map.of("materialId", mid, "caseNo", String.valueOf(r.get("case_no")), "error", String.valueOf(e.getMessage())));
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("direction", direction);
        report.put("total", rows.size());
        report.put("copied", copied);
        report.put("skipped", skipped);
        report.put("failed", failed);
        log.info("材料文件迁移[{}]: 共{} 复制{} 跳过{} 失败{}", direction, rows.size(), copied, skipped, failed.size());
        return report;
    }

    private MaterialFileStore minioStore() throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(props.getBucket()).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
        }
        return new MinioMaterialFileStore(client, props.getBucket());
    }

    private static boolean existsWithHash(MaterialFileStore store, String key, String expectHash) {
        try {
            return sha256(store.get(key)).equals(expectHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(bytes)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}