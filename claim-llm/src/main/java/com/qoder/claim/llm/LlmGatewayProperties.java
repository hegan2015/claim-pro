package com.qoder.claim.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型网关配置。路由表按 agentType 显式配置端点（baseUrl+model），
 * 不做跨平台模型名称翻译（官方 ID ≠ 第三方平台 ID，抄错会 404）。
 */
@Data
@ConfigurationProperties(prefix = "claim.llm")
public class LlmGatewayProperties {

    private Map<String, Endpoint> routes = new LinkedHashMap<>();
    private Endpoint fallback = new Endpoint();

    private int retryMax = 2;
    private long retryBackoffMs = 500;
    private int circuitFailureThreshold = 5;
    private long circuitOpenMs = 60_000;
    private long timeoutMs = 60_000;
    private double temperature = 0.2;
    private int maxTokens = 2048;
    /** 脱敏前置：身份证/银行卡/手机号在出域前替换。私有化端点可关闭。 */
    private boolean masking = true;

    @Data
    public static class Endpoint {
        private String baseUrl;
        private String model;
        private String apiKey;
        /** 私有化部署端点（数据不出域），脱敏可豁免 */
        private boolean privateDeployment;

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank()
                    && model != null && !model.isBlank()
                    && apiKey != null && !apiKey.isBlank();
        }

        public String key() {
            return (baseUrl == null ? "" : baseUrl.replaceAll("/+$", "")) + "|" + model;
        }
    }

    public Endpoint route(String agentType) {
        Endpoint e = routes.get(agentType);
        if (e == null || !e.configured()) {
            e = fallback;
        }
        return e;
    }
}