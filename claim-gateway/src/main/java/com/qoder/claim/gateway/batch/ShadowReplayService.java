package com.qoder.claim.gateway.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.claim.workflow.ChannelRouter;
import com.qoder.claim.workflow.RouteProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影子双跑：用候选阈值配置对历史案件重放分级，与生产实际通道比对，统计漂移。
 * 候选配置只读重放，不影响生产案件（影子模式）。上线新阈值前先跑影子验证漂移面。
 */
@Slf4j
@Service
public class ShadowReplayService {

    private final JdbcTemplate jdbc;
    private final RouteProperties baseline;
    private final ObjectMapper json = new ObjectMapper();

    public ShadowReplayService(JdbcTemplate jdbc, RouteProperties baseline) {
        this.jdbc = jdbc;
        this.baseline = baseline;
    }

    public Map<String, Object> run(Map<String, Object> candidate) {
        long t0 = System.currentTimeMillis();
        RouteProperties cand = merge(candidate);
        List<Map<String, Object>> cases = jdbc.queryForList(
                "SELECT case_no, risk_score, estimate_cents, recognition_confidence, channel FROM claim_case " +
                        "WHERE channel IS NOT NULL AND estimate_cents IS NOT NULL");
        int drift = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String actual = str(c.get("channel"));
            int risk = (int) num(c.get("risk_score"));
            long estimate = num(c.get("estimate_cents"));
            double conf = c.get("recognition_confidence") instanceof Number n ? n.doubleValue() : 0.9;
            String shadow = ChannelRouter.route(risk, estimate, conf, cand).name();
            if (!shadow.equals(actual)) {
                drift++;
                if (samples.size() < 50) {
                    samples.add(Map.of("caseNo", str(c.get("case_no")), "actual", actual,
                            "shadow", shadow, "risk", risk, "estimateCents", estimate));
                }
            }
        }
        long duration = System.currentTimeMillis() - t0;
        String configJson = toJson(cand);
        String samplesJson = toJson(samples);
        jdbc.update("INSERT INTO shadow_run(ts, config_json, total, drift_count, samples_json, duration_ms) VALUES(?,?,?,?,?,?)",
                t0, configJson, cases.size(), drift, samplesJson, duration);
        log.info("影子双跑完成: total={} drift={} {}ms", cases.size(), drift, duration);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", t0);
        out.put("total", cases.size());
        out.put("driftCount", drift);
        out.put("driftRate", cases.isEmpty() ? 0.0 : (double) drift / cases.size());
        out.put("candidate", cand);
        out.put("samples", samples);
        out.put("durationMs", duration);
        return out;
    }

    public Map<String, Object> latest() {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT * FROM shadow_run ORDER BY id DESC LIMIT 1");
        return runs.isEmpty() ? Map.of("run", (Object) null) : Map.of("run", runs.get(0));
    }

    private RouteProperties merge(Map<String, Object> c) {
        RouteProperties r = new RouteProperties();
        r.setFastLimitCents(longVal(c, "fastLimitCents", baseline.getFastLimitCents()));
        r.setStandardLimitCents(longVal(c, "standardLimitCents", baseline.getStandardLimitCents()));
        r.setRiskLow((int) longVal(c, "riskLow", baseline.getRiskLow()));
        r.setRiskHigh((int) longVal(c, "riskHigh", baseline.getRiskHigh()));
        Object conf = c.get("confidenceMin");
        r.setConfidenceMin(conf instanceof Number n ? n.doubleValue() : baseline.getConfidenceMin());
        return r;
    }

    private static long longVal(Map<String, Object> c, String k, long def) {
        Object v = c.get(k);
        return v instanceof Number n ? n.longValue() : def;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}