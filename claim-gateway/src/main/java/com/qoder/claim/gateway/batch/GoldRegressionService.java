package com.qoder.claim.gateway.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.claim.common.model.CalcResult;
import com.qoder.claim.common.model.Channel;
import com.qoder.claim.common.rule.DescriptionRules;
import com.qoder.claim.core.approval.ApprovalService;
import com.qoder.claim.core.calc.ClaimCalculator;
import com.qoder.claim.core.fraud.FraudScorer;
import com.qoder.claim.core.rule.CoverageDecision;
import com.qoder.claim.core.rule.CoverageRuleEngine;
import com.qoder.claim.workflow.ChannelRouter;
import com.qoder.claim.workflow.RouteProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 金标回归批处理：人工标注金标案纯代码重放确定性核心（核保→欺诈评分→分级→理算→审批数），
 * 逐字段比对期望裁决，任何漂移即失败并落库。不触碰 LLM、不写业务表。
 * 生产差异：XXL-Job 调度、金标库由核赔师持续扩充；重放与比对口径不变。
 */
@Slf4j
@Service
public class GoldRegressionService {

    private final CoverageRuleEngine coverage;
    private final ClaimCalculator calculator;
    private final FraudScorer fraud;
    private final ApprovalService approval;
    private final RouteProperties route;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<GoldCase> goldCases;

    @Autowired
    public GoldRegressionService(CoverageRuleEngine coverage, ClaimCalculator calculator, FraudScorer fraud,
                                 ApprovalService approval, RouteProperties route, JdbcTemplate jdbc) {
        this(coverage, calculator, fraud, approval, route, jdbc, loadClasspath());
    }

    GoldRegressionService(CoverageRuleEngine coverage, ClaimCalculator calculator, FraudScorer fraud,
                          ApprovalService approval, RouteProperties route, JdbcTemplate jdbc, List<GoldCase> goldCases) {
        this.coverage = coverage;
        this.calculator = calculator;
        this.fraud = fraud;
        this.approval = approval;
        this.route = route;
        this.jdbc = jdbc;
        this.goldCases = goldCases;
    }

    private static List<GoldCase> loadClasspath() {
        try (InputStream in = GoldRegressionService.class.getResourceAsStream("/gold-cases.json")) {
            if (in == null) {
                throw new IllegalStateException("金标数据集缺失: classpath:gold-cases.json");
            }
            return new ObjectMapper().findAndRegisterModules().readValue(in, new TypeReference<List<GoldCase>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("金标数据集加载失败", e);
        }
    }

    public Map<String, Object> run(String triggerBy) {
        long t0 = System.currentTimeMillis();
        int passed = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (GoldCase g : goldCases) {
            List<Map<String, Object>> mismatches = replay(g);
            boolean ok = mismatches.isEmpty();
            if (ok) {
                passed++;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("goldId", g.id);
            r.put("name", g.name);
            r.put("passed", ok);
            r.put("mismatches", mismatches);
            results.add(r);
        }
        long duration = System.currentTimeMillis() - t0;
        int total = goldCases.size();
        int failed = total - passed;

        KeyHolder kh = new GeneratedKeyHolder();
        final int passedCount = passed;
        final int failedCount = failed;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO gold_run(ts, trigger_by, total, passed, failed, duration_ms) VALUES(?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, t0);
            ps.setString(2, triggerBy);
            ps.setInt(3, total);
            ps.setInt(4, passedCount);
            ps.setInt(5, failedCount);
            ps.setLong(6, duration);
            return ps;
        }, kh);
        long runId = Objects.requireNonNull(kh.getKey()).longValue();
        for (Map<String, Object> r : results) {
            jdbc.update("INSERT INTO gold_case_result(run_id, gold_id, name, passed, mismatches_json, ts) VALUES(?,?,?,?,?,?)",
                    runId, r.get("goldId"), r.get("name"), Boolean.TRUE.equals(r.get("passed")) ? 1 : 0,
                    toJson(r.get("mismatches")), t0);
        }
        log.info("金标回归完成[{}]: {}/{} 通过, {}ms", triggerBy, passed, total, duration);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("ts", t0);
        out.put("triggerBy", triggerBy);
        out.put("total", total);
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("durationMs", duration);
        return out;
    }

    /** 单案重放：与生产管道同源（DescriptionRules / ChannelRouter / 核心引擎），返回差异清单。 */
    List<Map<String, Object>> replay(GoldCase g) {
        Map<String, Object> actual = new LinkedHashMap<>();
        CoverageDecision d = coverage.evaluate(g.policy, g.accidentDate, g.accidentType,
                DescriptionRules.drunkDriving(g.description),
                DescriptionRules.unlicensed(g.description),
                DescriptionRules.intentional(g.description));
        actual.put("covered", d.isCovered());
        actual.put("exclusions", d.getExclusions());
        if (d.isCovered()) {
            int risk = fraud.score(g.estimateCents, g.priorClaims, g.flaggedRepairShop, g.lateNightReport);
            Channel ch = ChannelRouter.route(risk, g.estimateCents, g.recognitionConfidence, route);
            CalcResult calc = calculator.calculate(g.id, g.estimateCents, g.policy.getDeductibleCents(),
                    g.policy.getDepreciationPercent(), g.policy.getLimitCents(),
                    DescriptionRules.liabilityPercent(g.description));
            actual.put("riskScore", risk);
            actual.put("channel", ch.name());
            actual.put("payableCents", calc.getPayableCents());
            actual.put("requiredApprovers", approval.requiredApprovers(calc.getPayableCents(), ch));
        } else {
            actual.put("payableCents", 0L);
        }
        List<Map<String, Object>> mismatches = new ArrayList<>();
        g.expected.forEach((field, exp) -> {
            Object act = actual.get(field);
            if (!sameValue(exp, act)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", field);
                m.put("expected", exp);
                m.put("actual", act);
                mismatches.add(m);
            }
        });
        return mismatches;
    }

    public Map<String, Object> latest() {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT * FROM gold_run ORDER BY id DESC LIMIT 1");
        Map<String, Object> out = new LinkedHashMap<>();
        if (runs.isEmpty()) {
            out.put("run", null);
            out.put("results", List.of());
            return out;
        }
        out.put("run", runs.get(0));
        out.put("results", jdbc.queryForList("SELECT * FROM gold_case_result WHERE run_id=? ORDER BY id",
                Rows.num(runs.get(0), "id")));
        return out;
    }

    public List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT * FROM gold_run ORDER BY id DESC LIMIT 20");
    }

    public int goldCaseCount() {
        return goldCases.size();
    }

    private static boolean sameValue(Object expected, Object actual) {
        if (expected instanceof Number en && actual instanceof Number an) {
            return en.longValue() == an.longValue();
        }
        return Objects.equals(expected, actual);
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}