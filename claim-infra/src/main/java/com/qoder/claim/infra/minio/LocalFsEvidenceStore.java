package com.qoder.claim.infra.minio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

/** 开发形态：本地文件系统证据库（claim.minio.enabled=false），哈希链机制与生产一致。 */
@Slf4j
public class LocalFsEvidenceStore extends AbstractEvidenceStore {

    private final Path root;

    public LocalFsEvidenceStore(JdbcTemplate jdbc, String dir) {
        super(jdbc);
        this.root = Path.of(dir);
        log.warn("MinIO 已禁用：证据写入本地文件系统 {}（开发模式，生产须启用 MinIO WORM）", dir);
    }

    @Override
    protected void putBytes(String objectKey, byte[] bytes) throws Exception {
        Path p = root.resolve(objectKey);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }

    @Override
    protected byte[] getBytes(String objectKey) throws Exception {
        return Files.readAllBytes(root.resolve(objectKey));
    }
}