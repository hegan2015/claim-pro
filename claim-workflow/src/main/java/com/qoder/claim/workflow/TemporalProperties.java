package com.qoder.claim.workflow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "claim.temporal")
public class TemporalProperties {
    /** 显式开关：关闭时启用本地开发管道（启动即告警，非生产编排）。 */
    private boolean enabled = true;
    private String target = "127.0.0.1:7233";
    private String namespace = "default";
    private String taskQueue = "claim-main";
}