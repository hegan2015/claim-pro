package com.qoder.claim.common.port;

/** 材料文件本体存储（MinIO 生产 / 本地磁盘开发），与证据链快照共用 claim.minio 开关。 */
public interface MaterialFileStore {

    void put(String objectKey, byte[] bytes, String contentType);

    byte[] get(String objectKey);
}