package com.qoder.claim.gateway.api;

import com.qoder.claim.agents.AgentFacade;
import com.qoder.claim.infra.rag.ClauseVectorIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F3 核保解释 RAG：条款原文向量化（共享栈 Qdrant+本地 ONNX embedding），
 * 解释时按命中条款码取原文 + 按事故描述语义检索相关条款，一并交给解释 agent 引用。
 * 向量容器不可达 → 降级为仅按条款码取原文（确定性部分不受影响）。
 */
@Slf4j
@Service
public class ClauseService {

    private final JdbcTemplate jdbc;
    private final ClauseVectorIndex index;
    private final AgentFacade agents;

    public ClauseService(JdbcTemplate jdbc, ClauseVectorIndex index, AgentFacade agents) {
        this.jdbc = jdbc;
        this.index = index;
        this.agents = agents;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        try {
            int n = index.indexAll(jdbc.queryForList("SELECT * FROM clause_text"));
            log.info("条款 RAG 就绪: {} 条", n);
        } catch (Exception e) {
            log.warn("条款向量索引不可用（降级为条款码直查）: {}", e.getMessage());
        }
    }

    public List<ClauseVectorIndex.ClauseHit> search(String query, int limit) throws Exception {
        return index.search(query, limit);
    }

    /** 核保解释：确定性判定结果 + 条款原文 → agent 翻译为客户可读文本（判定权仍在规则引擎）。 */
    public Map<String, Object> explain(String caseNo, String description) {
        List<Map<String, Object>> cov = jdbc.queryForList(
                "SELECT output_json FROM claim_decision WHERE case_no=? AND step='COVERAGE' ORDER BY ts DESC, id DESC LIMIT 1",
                caseNo);
        String decisionJson = cov.isEmpty() ? "{}" : String.valueOf(cov.get(0).get("output_json"));
        List<Map<String, Object>> cited = new ArrayList<>();
        // 按命中/免责条款码直查原文（确定性兜底）
        for (String code : extractCodes(decisionJson)) {
            cited.addAll(jdbc.queryForList("SELECT clause_id, code, title, body FROM clause_text WHERE code=?", code));
        }
        // 语义检索补充相关条款（RAG 增强，可降级）
        List<ClauseVectorIndex.ClauseHit> hits = List.of();
        try {
            hits = index.search(description == null ? "" : description, 3);
            for (var h : hits) {
                if (cited.stream().noneMatch(c -> String.valueOf(c.get("clause_id")).equals(h.clauseId()))) {
                    cited.add(Map.of("clause_id", h.clauseId(), "code", h.code(), "title", h.title(), "body", h.body()));
                }
            }
        } catch (Exception e) {
            log.warn("条款语义检索降级 case={}: {}", caseNo, e.getMessage());
        }
        String context = "{\"decision\":" + (decisionJson.startsWith("{") ? decisionJson : "{}")
                + ",\"clauses\":" + toJson(cited) + ",\"description\":" + toJson(description) + "}";
        String explanation = agents.explainCoverage(context);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseNo", caseNo);
        out.put("explanation", explanation);
        out.put("citedClauses", cited);
        out.put("vectorHits", hits.size());
        return out;
    }

    private static List<String> extractCodes(String decisionJson) {
        List<String> codes = new ArrayList<>();
        var m = java.util.regex.Pattern.compile("(CLAUSE-[A-Z-]+|EXCL-[A-Z]+)").matcher(decisionJson);
        while (m.find()) {
            if (!codes.contains(m.group(1))) {
                codes.add(m.group(1));
            }
        }
        return codes;
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}