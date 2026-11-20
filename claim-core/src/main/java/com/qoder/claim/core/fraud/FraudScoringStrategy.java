package com.qoder.claim.core.fraud;

/** 欺诈评分策略：规则评分器与 XGBoost(JPMML) 模型评分器共用接口，生产可切换。 */
public interface FraudScoringStrategy {

    int score(long estimateCents, int priorClaims, boolean flaggedRepairShop, boolean lateNightReport);

    /** 策略名（落审计/证据，便于回放区分）。 */
    String name();
}