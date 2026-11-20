package com.qoder.claim.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 模型网关客户端（OpenAI 兼容协议，JDK HttpClient 零 SDK）。
 * 移植 cs-llm 已验证模式：按端点熔断 + 分类退避重试 + 流式/工具双模式 + usage 计量；
 * 新增：agentType 路由、出域脱敏、端点故障切换。
 */
@Slf4j
@Component
public class LlmGatewayClient {

    private final LlmGatewayProperties props;
    private final MaskingService masking;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    private static final class Breaker {
        final AtomicInteger fails = new AtomicInteger();
        volatile long openUntil;
    }

    @Data
    public static class RawToolCall {
        private String id;
        private String name;
        private String argumentsJson;
    }

    @Data
    public static class Outcome {
        private String content;
        private List<RawToolCall> toolCalls;
        private long promptTokens;
        private long completionTokens;
        private String endpointKey;
        private String model;
    }

    public LlmGatewayClient(LlmGatewayProperties props, MaskingService masking) {
        this.props = props;
        this.masking = masking;
    }

    public boolean available(String agentType) {
        LlmGatewayProperties.Endpoint ep = props.route(agentType);
        if (!ep.configured()) {
            return false;
        }
        Breaker br = breakers.get(ep.key());
        return br == null || System.currentTimeMillis() >= br.openUntil;
    }

    /**
     * @param onToken 非 null 走流式（最终回答轮）；null 走非流式（工具调用轮）。
     */
    public Outcome chat(String agentType, List<Map<String, Object>> messages,
                        List<Map<String, Object>> tools, Consumer<String> onToken) {
        LlmGatewayProperties.Endpoint ep = props.route(agentType);
        if (!ep.configured()) {
            throw new LlmGatewayException("模型端点未配置: " + agentType);
        }
        Breaker br = breakers.computeIfAbsent(ep.key(), k -> new Breaker());
        if (System.currentTimeMillis() < br.openUntil) {
            throw new LlmGatewayException("模型端点熔断中: " + ep.key()).markCircuit();
        }
        List<Map<String, Object>> outbound =
                (props.isMasking() && !ep.isPrivateDeployment()) ? maskMessages(messages) : messages;

        AtomicBoolean emitted = new AtomicBoolean();
        Consumer<String> sink = onToken == null ? null : piece -> {
            emitted.set(true);
            onToken.accept(piece);
        };
        int maxAttempts = Math.max(1, props.getRetryMax() + 1);
        LlmGatewayException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Outcome r = doChat(ep, outbound, tools, sink);
                br.fails.set(0);
                r.setEndpointKey(ep.key());
                r.setModel(ep.getModel());
                return r;
            } catch (Exception e) {
                LlmGatewayException le = e instanceof LlmGatewayException x ? x
                        : new LlmGatewayException("模型调用失败: " + e.getMessage(), e);
                if (emitted.get()) {
                    le.markStreamed();
                }
                last = le;
                if (attempt < maxAttempts && retryable(le)) {
                    long backoff = props.getRetryBackoffMs() * (1L << (attempt - 1));
                    log.warn("端点 {} 第 {} 次失败（{}），{}ms 后重试", ep.key(), attempt, le.getMessage(), backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                break;
            }
        }
        recordFailure(br, ep.key());
        throw last;
    }

    /** 429/5xx/网络超时可重试；4xx 参数鉴权错误、已出流的流式中断不重试。 */
    private static boolean retryable(LlmGatewayException e) {
        if (e.isStreamed()) {
            return false;
        }
        int status = e.getHttpStatus();
        if (status > 0) {
            return status == 429 || status >= 500;
        }
        return e.getCause() != null;
    }

    private void recordFailure(Breaker br, String key) {
        int n = br.fails.incrementAndGet();
        if (n >= props.getCircuitFailureThreshold()) {
            br.openUntil = System.currentTimeMillis() + props.getCircuitOpenMs();
            log.warn("端点 {} 连续失败 {} 次，熔断 {}ms", key, n, props.getCircuitOpenMs());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maskMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (Map<String, Object> m : messages) {
            Map<String, Object> copy = new LinkedHashMap<>(m);
            Object content = copy.get("content");
            if (content instanceof String s) {
                copy.put("content", masking.mask(s));
            } else if (content instanceof List<?> parts) {
                // 多模态消息：只脱敏文本块，图像块原样（base64 不含可识别文本）
                List<Object> masked = new ArrayList<>(parts.size());
                for (Object p : parts) {
                    if (p instanceof Map<?, ?> pm && "text".equals(pm.get("type")) && pm.get("text") instanceof String t) {
                        Map<Object, Object> mc = new LinkedHashMap<>(pm);
                        mc.put("text", masking.mask(t));
                        masked.add(mc);
                    } else {
                        masked.add(p);
                    }
                }
                copy.put("content", masked);
            }
            out.add(copy);
        }
        return out;
    }

    private Outcome doChat(LlmGatewayProperties.Endpoint ep, List<Map<String, Object>> messages,
                           List<Map<String, Object>> tools, Consumer<String> onToken) throws Exception {
        boolean stream = onToken != null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ep.getModel());
        body.put("messages", messages);
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        if (stream) {
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ep.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ep.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        Outcome result = new Outcome();
        if (!stream) {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LlmGatewayException("模型 HTTP " + resp.statusCode() + ": " + truncate(resp.body()))
                        .withHttpStatus(resp.statusCode());
            }
            JsonNode root = json.readTree(resp.body());
            JsonNode msg = root.path("choices").path(0).path("message");
            result.setContent(msg.path("content").asText(null));
            result.setToolCalls(parseToolCalls(msg.path("tool_calls")));
            fillUsage(result, root.path("usage"));
        } else {
            HttpResponse<Stream<String>> resp = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() / 100 != 2) {
                String err = resp.body().reduce("", (a, b) -> a + b);
                throw new LlmGatewayException("模型 HTTP " + resp.statusCode() + ": " + truncate(err))
                        .withHttpStatus(resp.statusCode());
            }
            StringBuilder content = new StringBuilder();
            Map<Integer, RawToolCall> partial = new TreeMap<>();
            try (Stream<String> lines = resp.body()) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode chunk = json.readTree(data);
                    JsonNode delta = chunk.path("choices").path(0).path("delta");
                    String piece = delta.path("content").asText(null);
                    if (piece != null && !piece.isEmpty()) {
                        content.append(piece);
                        onToken.accept(piece);
                    }
                    mergeStreamToolCalls(delta.path("tool_calls"), partial);
                    fillUsage(result, chunk.path("usage"));
                }
            }
            result.setContent(content.length() == 0 ? null : content.toString());
            result.setToolCalls(partial.isEmpty() ? null : new ArrayList<>(partial.values()));
        }
        return result;
    }

    private List<RawToolCall> parseToolCalls(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<RawToolCall> calls = new ArrayList<>();
        for (JsonNode tc : node) {
            RawToolCall c = new RawToolCall();
            c.setId(tc.path("id").asText());
            c.setName(tc.path("function").path("name").asText());
            c.setArgumentsJson(tc.path("function").path("arguments").asText("{}"));
            calls.add(c);
        }
        return calls;
    }

    private void mergeStreamToolCalls(JsonNode node, Map<Integer, RawToolCall> partial) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode tc : node) {
            int idx = tc.path("index").asInt(0);
            RawToolCall cur = partial.computeIfAbsent(idx, k -> {
                RawToolCall c = new RawToolCall();
                c.setId("");
                c.setName("");
                c.setArgumentsJson("");
                return c;
            });
            if (tc.hasNonNull("id")) {
                cur.setId(tc.get("id").asText());
            }
            JsonNode fn = tc.path("function");
            if (fn.hasNonNull("name")) {
                cur.setName(cur.getName() + fn.get("name").asText());
            }
            if (fn.hasNonNull("arguments")) {
                cur.setArgumentsJson(cur.getArgumentsJson() + fn.get("arguments").asText());
            }
        }
    }

    private void fillUsage(Outcome result, JsonNode usage) {
        if (usage != null && usage.isObject()) {
            result.setPromptTokens(usage.path("prompt_tokens").asLong(0));
            result.setCompletionTokens(usage.path("completion_tokens").asLong(0));
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}