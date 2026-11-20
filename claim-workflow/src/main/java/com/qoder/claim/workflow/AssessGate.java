package com.qoder.claim.workflow;

/**
 * 定损门禁纯函数：编排管道与金标回归共用。
 * 空定损（估损≤0）或描述索赔与估损严重背离 → 强制人工，堵住「零估损走 FAST 零赔付结案」空子。
 */
public final class AssessGate {

    private AssessGate() {
    }

    /** 返回转人工原因；null 表示放行。金额单位分。 */
    public static String reason(long estimateCents, long claimedCents, double confidence,
                                AssessGateProperties p, RouteProperties route) {
        if (!p.isEnabled()) {
            return null;
        }
        if (estimateCents <= 0) {
            return "定损项为空或估损为0，强制人工核实";
        }
        if (claimedCents > 0 && claimedCents > estimateCents * p.getDivergenceRatio()) {
            return String.format("描述索赔¥%.2f与估损¥%.2f背离超%.1f倍，强制人工核实",
                    claimedCents / 100.0, estimateCents / 100.0, p.getDivergenceRatio());
        }
        if (confidence < route.getConfidenceMin()) {
            return String.format("识别置信度%.2f低于阈值%.2f，强制人工录入", confidence, route.getConfidenceMin());
        }
        return null;
    }
}