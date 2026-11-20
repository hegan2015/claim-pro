package com.qoder.claim.core.fraud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelFraudScorerTest {

    private final ModelFraudScorer model = new ModelFraudScorer();
    private final FraudScorer rules = new FraudScorer();

    @Test
    void modelLoadsAndScoresInRange() {
        assertTrue(model.available(), "PMML 模型应可加载");
        int s = model.score(420000, 0, false, false);
        assertTrue(s >= 0 && s <= 100, "分数须在 0-100: " + s);
    }

    @Test
    void highRiskScoresHigherThanLowRisk() {
        int low = model.score(200000, 0, false, false);
        int high = model.score(12000000, 5, true, true);
        assertTrue(high > low, "高风险特征组合分数应更高: low=" + low + " high=" + high);
    }

    @Test
    void modelApproximatesRuleBaseline() {
        // 演示模型以规则评分为基线训练，典型样本应接近（允许平滑偏差）
        long[] est = {200000, 420000, 3200000, 6800000, 12000000};
        int[][] flags = {{0, 0, 0}, {0, 0, 0}, {1, 0, 0}, {2, 1, 1}, {0, 0, 0}};
        for (int i = 0; i < est.length; i++) {
            int r = rules.score(est[i], flags[i][0], flags[i][1] == 1, flags[i][2] == 1);
            int m = model.score(est[i], flags[i][0], flags[i][1] == 1, flags[i][2] == 1);
            assertTrue(Math.abs(r - m) <= 25, "模型与规则基线偏差应有限: est=" + est[i] + " rule=" + r + " model=" + m);
        }
    }

    @Test
    void routerDefaultsToRulesAndFallsBack() {
        FraudScoringRouter ruleRouter = new FraudScoringRouter(rules, model, "rules");
        assertEquals("RULES", ruleRouter.name());
        assertEquals(rules.score(6800000, 2, true, true), ruleRouter.score(6800000, 2, true, true));

        FraudScoringRouter modelRouter = new FraudScoringRouter(rules, model, "model");
        assertEquals("XGBOOST", modelRouter.name());

        // 模型不可用时回退规则
        ModelFraudScorer broken = new ModelFraudScorer() {
            @Override
            public boolean available() {
                return false;
            }
        };
        FraudScoringRouter fallback = new FraudScoringRouter(rules, broken, "model");
        assertEquals("RULES", fallback.name());
    }
}