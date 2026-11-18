package com.qoder.claim.llm;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * 流式适配器：与 GatewayChatModel 共享 wire 转换，委托同一模型网关客户端。
 * LC4j 1.0.1 中 ChatModel 与 StreamingChatModel 的默认方法签名冲突，须分类实现。
 */
public class GatewayStreamingChatModel implements StreamingChatModel {

    private final GatewayChatModel delegate;

    public GatewayStreamingChatModel(GatewayChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        try {
            LlmGatewayClient.Outcome out = delegate.gateway().chat(delegate.agentType(),
                    delegate.toWireMessages(request.messages()), delegate.toWireTools(request.toolSpecifications()),
                    handler::onPartialResponse);
            handler.onCompleteResponse(delegate.toResponse(out));
        } catch (Exception e) {
            handler.onError(e);
        }
    }
}