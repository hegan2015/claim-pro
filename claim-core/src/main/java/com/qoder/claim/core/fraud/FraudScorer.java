package com.qoder.claim.core.fraud;

import org.springframework.stereotype.Component;

/**
 * 欺诈评分器（确定性，非 LLM）。
 * P0 为规则评分；生产替换为 XGBoost(JPMML)+NebulaGraph 图特征适配器，接口不变。
 * agent 只消费分数与线索做报告，不参与打分。
 */
@Component
public class FraudScorer {

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
}