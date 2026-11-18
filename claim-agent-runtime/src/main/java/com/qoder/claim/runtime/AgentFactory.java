package com.qoder.claim.runtime;

import com.qoder.claim.llm.GatewayChatModel;
import com.qoder.claim.llm.GatewayStreamingChatModel;
import com.qoder.claim.llm.LlmGatewayClient;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

/**
 * AiServices 装配工厂：每个 agent 一个声明式接口 + 专属 GatewayChatModel（路由/脱敏/熔断收口）
 * + 工具集（方法体内强制走工具网关）。禁止 OpenAiChatModel 直配 apiKey。
 */
@Component
public class AgentFactory {

    private final LlmGatewayClient gateway;

    public AgentFactory(LlmGatewayClient gateway) {
        this.gateway = gateway;
    }

    public <T> T build(Class<T> agentInterface, String agentType, Object... tools) {
        GatewayChatModel model = new GatewayChatModel(gateway, agentType);
        var builder = AiServices.builder(agentInterface)
                .chatModel(model)
                .streamingChatModel(new GatewayStreamingChatModel(model))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20));
        if (tools.length > 0) {
            builder.tools(tools);
        }
        return builder.build();
    }

    public boolean available(String agentType) {
        return gateway.available(agentType);
    }
}