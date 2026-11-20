package com.qoder.claim.core.fraud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 欺诈评分路由：claim.fraud.strategy=rules（默认，确定性基线）| model（XGBoost）。
 * 选 model 但模型不可用 → 自动回退规则并告警，业务不停摆。
 * 金标回归始终用规则评分器（确定性基线），不经本路由。
 */
@Slf4j
@Primary
@Component
public class FraudScoringRouter implements FraudScoringStrategy {

    private final FraudScorer rules;
    private final ModelFraudScorer model;
    private final boolean useModel;

    public FraudScoringRouter(FraudScorer rules, ModelFraudScorer model,
                              @Value("${claim.fraud.strategy:rules}") String strategy) {
        this.rules = rules;
        this.model = model;
        this.useModel = "model".equalsIgnoreCase(strategy);
        if (this.useModel && !model.available()) {
            log.warn("claim.fraud.strategy=model 但模型不可用，回退规则评分");
        }
        log.info("欺诈评分策略: {}", name());
    }

    private FraudScoringStrategy active() {
        return useModel && model.available() ? model : rules;
    }

    @Override
    public int score(long estimateCents, int priorClaims, boolean flaggedRepairShop, boolean lateNightReport) {
        return active().score(estimateCents, priorClaims, flaggedRepairShop, lateNightReport);
    }

    @Override
    public String name() {
        return active().name();
    }
}