package com.qoder.claim.workflow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 灰度发布：按案件号哈希分桶 + 机构/险种白名单，将流量切到候选阈值配置（不影响基线）。 */
@Data
@ConfigurationProperties(prefix = "claim.canary")
public class CanaryProperties {
    private boolean enabled = false;
    /** 灰度百分比 0-100。 */
    private int percent = 0;
    /** 机构白名单（逗号分隔，空=全部）。 */
    private String orgs = "";
    /** 险种白名单（逗号分隔，空=全部）。 */
    private String insuranceTypes = "";
    /** 候选阈值（null=沿用基线）。 */
    private Long candidateFastLimitCents;
    private Long candidateStandardLimitCents;
    private Integer candidateRiskLow;
    private Integer candidateRiskHigh;
    private Double candidateConfidenceMin;
}