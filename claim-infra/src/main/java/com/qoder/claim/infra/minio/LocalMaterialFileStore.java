package com.qoder.claim.infra.minio;

import com.qoder.claim.common.port.MaterialFileStore;

import java.nio.file.Files;
import java.nio.file.Path;

/** 开发形态：材料文件落本地磁盘（claim.minio.enabled=false），contentType 由数据库列提供。 */
public class LocalMaterialFileStore implements MaterialFileStore {

    private final Path root;

    public LocalMaterialFileStore(String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
        try {
            Path p = resolve(objectKey);
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        } catch (Exception e) {
            throw new IllegalStateException("材料文件写入失败: " + objectKey, e);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try {
            return Files.readAllBytes(resolve(objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("材料文件读取失败: " + objectKey, e);
        }
    }

    private Path resolve(String objectKey) {
        Path p = root.resolve(objectKey).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("非法对象键: " + objectKey);
        }
        return p;
    }
}