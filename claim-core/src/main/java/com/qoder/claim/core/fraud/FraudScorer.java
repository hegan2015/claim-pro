package com.qoder.claim.core.fraud;

import org.springframework.stereotype.Component;

/**
 * 欺诈评分器（确定性规则，非 LLM）。
 * 作为金标回归的确定性基线与模型不可用时的兜底；生产可切 XGBoost(JPMML)，接口不变。
 * agent 只消费分数与线索做报告，不参与打分。
 */
@Component
public class FraudScorer implements FraudScoringStrategy {

    @Override
    public int score(long estimateCents, int priorClaims, boolean flaggedRepairShop, boolean lateNightReport) {
        int score = 0;
        if (estimateCents > 50_000_00L) {
            score += 25;
        } else if (estimateCents > 10_000_00L) {
            score += 10;
        }
        score += Math.min(30, priorClaims * 10);
        if (flaggedRepairShop) {
            score += 30;
        }
        if (lateNightReport) {
            score += 10;
        }
        return Math.min(100, score);
    }

    @Override
    public String name() {
        return "RULES";
    }
}