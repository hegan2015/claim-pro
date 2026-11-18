package com.qoder.claim.workflow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 三通道分级阈值（配置中心可调）。金额单位：分。 */
@Data
@ConfigurationProperties(prefix = "claim.route")
public class RouteProperties {
    private long fastLimitCents = 5000_00;
    private long standardLimitCents = 50_000_00;
    private int riskLow = 30;
    private int riskHigh = 70;
    private double confidenceMin = 0.85;
}