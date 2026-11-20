package com.qoder.claim.workflow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 定损门禁阈值：空定损/索赔背离强制转人工（确定性规则，LLM 禁入）。 */
@Data
@ConfigurationProperties(prefix = "claim.gate")
public class AssessGateProperties {
    private boolean enabled = true;
    /** 描述索赔金额超过估损的该倍数即判定严重背离。 */
    private double divergenceRatio = 3.0;
}