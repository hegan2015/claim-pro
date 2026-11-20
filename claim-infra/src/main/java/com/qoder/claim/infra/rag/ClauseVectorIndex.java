package com.qoder.claim.infra.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 条款向量索引：JDK HttpClient 直连 embedding/Qdrant REST（无 SDK，与 cs-pro VectorIndex 同模式）。
 * collection 余弦相似度；point id = clauseId 的 name-based UUID（增改幂等 upsert）。
 * 容器不可达抛 IllegalStateException，由调用方降级（不阻塞核保主链路）。
 */
@Slf4j
public class ClauseVectorIndex {

    public record ClauseHit(String clauseId, String code, String title, String body, double score) {
    }

    private final ClauseRagProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean collectionReady;

    public ClauseVectorIndex(ClauseRagProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.isEnabled();
    }

    /** 全量幂等索引条款原文。 */
    public int indexAll(List<Map<String, Object>> clauses) throws Exception {
        if (!props.isEnabled() || clauses.isEmpty()) {
            return 0;
        }
        List<String> texts = new ArrayList<>();
        for (Map<String, Object> c : clauses) {
            texts.add(c.get("title") + " " + c.get("body"));
        }
        List<List<Double>> vectors = embed(texts);
        ensureCollection(vectors.get(0).size());
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < clauses.size(); i++) {
            Map<String, Object> c = clauses.get(i);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("clauseId", c.get("clause_id"));
            payload.put("code", c.get("code"));
            payload.put("title", c.get("title"));
            payload.put("body", c.get("body"));
            points.add(Map.of(
                    "id", UUID.nameUUIDFromBytes(String.valueOf(c.get("clause_id")).getBytes(StandardCharsets.UTF_8)).toString(),
                    "vector", vectors.get(i),
                    "payload", payload));
        }
        request("PUT", props.getQdrantUrl() + "/collections/" + props.getCollection() + "/points?wait=true",
                Map.of("points", points));
        log.info("条款向量索引完成: {} 条 → collection {}", clauses.size(), props.getCollection());
        return clauses.size();
    }

    public List<ClauseHit> search(String query, int limit) throws Exception {
        if (!props.isEnabled()) {
            return List.of();
        }
        List<Double> vec = embed(List.of(query)).get(0);
        ensureCollection(vec.size());
        JsonNode resp = request("POST", props.getQdrantUrl() + "/collections/" + props.getCollection() + "/points/search",
                Map.of("vector", vec, "limit", limit, "with_payload", true));
        List<ClauseHit> hits = new ArrayList<>();
        for (JsonNode r : resp.path("result")) {
            JsonNode p = r.path("payload");
            hits.add(new ClauseHit(p.path("clauseId").asText(), p.path("code").asText(),
                    p.path("title").asText(), p.path("body").asText(), r.path("score").asDouble()));
        }
        return hits;
    }

    private List<List<Double>> embed(List<String> texts) throws Exception {
        JsonNode resp = request("POST", props.getEmbeddingUrl() + "/embed", Map.of("texts", texts));
        List<List<Double>> out = new ArrayList<>();
        for (JsonNode v : resp.path("vectors")) {
            List<Double> vec = new ArrayList<>();
            v.forEach(x -> vec.add(x.asDouble()));
            out.add(vec);
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("embedding 返回空向量");
        }
        return out;
    }

    private void ensureCollection(int dim) throws Exception {
        if (collectionReady) {
            return;
        }
        try {
            request("GET", props.getQdrantUrl() + "/collections/" + props.getCollection(), null);
        } catch (Exception e) {
            request("PUT", props.getQdrantUrl() + "/collections/" + props.getCollection(),
                    Map.of("vectors", Map.of("size", dim, "distance", "Cosine")));
        }
        collectionReady = true;
    }

    private JsonNode request(String method, String url, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (body != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("RAG 依赖响应异常 " + resp.statusCode() + ": " + url);
        }
        return json.readTree(resp.body());
    }
}