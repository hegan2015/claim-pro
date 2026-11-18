package com.qoder.claim.infra.minio;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "claim.minio")
public class MinioProperties {
    /** 关闭时使用本地文件系统证据库（开发模式，启动即告警），哈希链机制不变。 */
    private boolean enabled = true;
    private String endpoint = "http://127.0.0.1:9000";
    private String accessKey;
    private String secretKey;
    private String bucket = "claim-evidence";
    private String localDir = "data/evidence";
}