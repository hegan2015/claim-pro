package com.qoder.claim.workflow;

import com.qoder.claim.common.model.Channel;

/** 三通道分级纯函数：编排管道与金标回归共用，阈值唯一来源 RouteProperties。 */
public final class ChannelRouter {

    private ChannelRouter() {
    }

    public static Channel route(int riskScore, long estimateCents, double confidence, RouteProperties p) {
        if (riskScore >= p.getRiskHigh() || estimateCents > p.getStandardLimitCents()) {
            return Channel.INVESTIGATE;
        }
        if (estimateCents < p.getFastLimitCents() && riskScore < p.getRiskLow()
                && confidence >= p.getConfidenceMin()) {
            return Channel.FAST;
        }
        return Channel.STANDARD;
    }
}