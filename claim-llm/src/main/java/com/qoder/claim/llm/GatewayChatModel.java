package com.qoder.claim.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j 自定义 ChatModel：企业控制点收口处。
 * 上层 AiServices 的全部调用都委托模型网关客户端（路由/脱敏/熔断/重试/计量），
 * 禁止 OpenAiChatModel 直配 apiKey 的 demo 用法。
 */
public class GatewayChatModel implements ChatModel {

    private final LlmGatewayClient gateway;
    private final String agentType;
    private final ObjectMapper mapper = new ObjectMapper();

    public GatewayChatModel(LlmGatewayClient gateway, String agentType) {
        this.gateway = gateway;
        this.agentType = agentType;
    }

    public String agentType() {
        return agentType;
    }

    LlmGatewayClient gateway() {
        return gateway;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        LlmGatewayClient.Outcome out = gateway.chat(agentType,
                toWireMessages(request.messages()), toWireTools(request.toolSpecifications()), null);
        return toResponse(out);
    }

    ChatResponse toResponse(LlmGatewayClient.Outcome out) {
        AiMessage ai;
        FinishReason reason;
        if (out.getToolCalls() != null && !out.getToolCalls().isEmpty()) {
            List<ToolExecutionRequest> reqs = new ArrayList<>();
            for (LlmGatewayClient.RawToolCall tc : out.getToolCalls()) {
                reqs.add(ToolExecutionRequest.builder()
                        .id(tc.getId())
                        .name(tc.getName())
                        .arguments(tc.getArgumentsJson() == null ? "{}" : tc.getArgumentsJson())
                        .build());
            }
            ai = AiMessage.from(reqs);
            reason = FinishReason.TOOL_EXECUTION;
        } else {
            ai = AiMessage.from(out.getContent() == null ? "" : out.getContent());
            reason = FinishReason.STOP;
        }
        return ChatResponse.builder()
                .aiMessage(ai)
                .tokenUsage(new TokenUsage((int) out.getPromptTokens(), (int) out.getCompletionTokens()))
                .finishReason(reason)
                .build();
    }

    List<Map<String, Object>> toWireMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            Map<String, Object> w = new LinkedHashMap<>();
            if (m instanceof SystemMessage s) {
                w.put("role", "system");
                w.put("content", s.text());
            } else if (m instanceof UserMessage u) {
                w.put("role", "user");
                w.put("content", u.singleText());
            } else if (m instanceof AiMessage a) {
                w.put("role", "assistant");
                if (a.text() != null) {
                    w.put("content", a.text());
                }
                if (a.hasToolExecutionRequests()) {
                    List<Map<String, Object>> tcs = new ArrayList<>();
                    for (ToolExecutionRequest r : a.toolExecutionRequests()) {
                        tcs.add(Map.of("id", r.id(), "type", "function",
                                "function", Map.of("name", r.name(),
                                        "arguments", r.arguments() == null ? "{}" : r.arguments())));
                    }
                    w.put("tool_calls", tcs);
                }
            } else if (m instanceof ToolExecutionResultMessage t) {
                w.put("role", "tool");
                w.put("tool_call_id", t.id());
                w.put("content", t.text());
            } else {
                throw new IllegalArgumentException("不支持的消息类型: " + m.getClass());
            }
            out.add(w);
        }
        return out;
    }

    List<Map<String, Object>> toWireTools(List<ToolSpecification> specs) {
        if (specs == null || specs.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpecification s : specs) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", s.name());
            fn.put("description", s.description() == null ? "" : s.description());
            // 不用框架内部 Json.toJson：它会丢 type 字段并混入 null/definitions，DeepSeek 严格校验直接 400
            fn.put("parameters", s.parameters() == null
                    ? Map.of("type", "object", "properties", Map.of())
                    : schemaToWire(s.parameters()));
            out.add(Map.of("type", "function", "function", fn));
        }
        return out;
    }

    private static Map<String, Object> schemaToWire(JsonSchemaElement el) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (el instanceof JsonObjectSchema o) {
            m.put("type", "object");
            if (o.description() != null) m.put("description", o.description());
            Map<String, Object> props = new LinkedHashMap<>();
            if (o.properties() != null) {
                o.properties().forEach((k, v) -> props.put(k, schemaToWire(v)));
            }
            m.put("properties", props);
            if (o.required() != null && !o.required().isEmpty()) m.put("required", o.required());
        } else if (el instanceof JsonStringSchema s) {
            m.put("type", "string");
            if (s.description() != null) m.put("description", s.description());
        } else if (el instanceof JsonIntegerSchema s) {
            m.put("type", "integer");
            if (s.description() != null) m.put("description", s.description());
        } else if (el instanceof JsonNumberSchema s) {
            m.put("type", "number");
            if (s.description() != null) m.put("description", s.description());
        } else if (el instanceof JsonBooleanSchema s) {
            m.put("type", "boolean");
            if (s.description() != null) m.put("description", s.description());
        } else if (el instanceof JsonEnumSchema s) {
            m.put("type", "string");
            m.put("enum", s.enumValues());
            if (s.description() != null) m.put("description", s.description());
        } else if (el instanceof JsonArraySchema a) {
            m.put("type", "array");
            if (a.description() != null) m.put("description", a.description());
            if (a.items() != null) m.put("items", schemaToWire(a.items()));
        } else {
            throw new IllegalArgumentException("不支持的工具参数类型: " + el.getClass().getSimpleName());
        }
        return m;
    }
}