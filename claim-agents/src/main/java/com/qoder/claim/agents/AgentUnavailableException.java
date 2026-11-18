package com.qoder.claim.agents;

/** agent 依赖的模型端点未配置/熔断中。workflow 捕获后降级 MANUAL 通道（显式降级，非静默 mock）。 */
public class AgentUnavailableException extends RuntimeException {

    public AgentUnavailableException(String agentType, String reason) {
        super("agent 不可用[" + agentType + "]: " + reason);
    }

    public AgentUnavailableException(String agentType, Throwable cause) {
        super("agent 不可用[" + agentType + "]: " + cause.getMessage(), cause);
    }
}