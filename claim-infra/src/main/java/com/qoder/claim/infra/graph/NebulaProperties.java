package com.qoder.claim.infra.graph;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** NebulaGraph 实时图存储（可选增强，默认关闭；T+1 关系库重建保留为对账基线）。 */
@Data
@ConfigurationProperties(prefix = "claim.graph.nebula")
public class NebulaProperties {
    private boolean enabled = false;
    private String hosts = "127.0.0.1:9669";
    private String space = "claim_graph";
    private String user = "root";
    private String password = "nebula";
}