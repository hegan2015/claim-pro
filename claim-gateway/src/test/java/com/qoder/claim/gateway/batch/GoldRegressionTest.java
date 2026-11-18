package com.qoder.claim.gateway.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.claim.core.approval.ApprovalService;
import com.qoder.claim.core.calc.ClaimCalculator;
import com.qoder.claim.core.fraud.FraudScorer;
import com.qoder.claim.core.rule.CoverageRuleEngine;
import com.qoder.claim.workflow.RouteProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoldRegressionTest {

    private JdbcTemplate jdbc;
    private GoldRegressionService gold;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:gold" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE gold_run(id BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT, trigger_by VARCHAR(16), total INT, passed INT, failed INT, duration_ms BIGINT)");
        jdbc.execute("CREATE TABLE gold_case_result(id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id BIGINT, gold_id VARCHAR(32), name VARCHAR(128), passed INT, mismatches_json VARCHAR(2000), ts BIGINT)");
        gold = new GoldRegressionService(new CoverageRuleEngine(), new ClaimCalculator(), new FraudScorer(),
                new ApprovalService(jdbc), new RouteProperties(), jdbc);
    }

    @Test
    void allGoldCasesPass() {
        Map<String, Object> r = gold.run("TEST");
        assertEquals(15, r.get("total"));
        assertEquals(15, r.get("passed"));
        assertEquals(0, r.get("failed"));
        Map<String, Object> latest = gold.latest();
        assertNotNull(latest.get("run"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) latest.get("results");
        assertEquals(15, results.size());
        assertTrue(results.stream().allMatch(x -> Integer.valueOf(1).equals(Rows.val(x, "passed"))));
        assertEquals(1, gold.history().size());
    }

    @Test
    void tamperedExpectationIsDetected() throws Exception {
        List<GoldCase> cases = loadFixture();
        cases.get(0).expected.put("payableCents", 999999); // 篡改 GOLD-001 期望金额
        cases.get(1).expected.put("channel", "FAST");       // 篡改 GOLD-002 期望通道
        GoldRegressionService tampered = new GoldRegressionService(new CoverageRuleEngine(), new ClaimCalculator(),
                new FraudScorer(), new ApprovalService(jdbc), new RouteProperties(), jdbc, cases);
        Map<String, Object> r = tampered.run("TEST");
        assertEquals(2, r.get("failed"));
        List<Map<String, Object>> mismatches = tampered.replay(cases.get(0));
        assertEquals("payableCents", mismatches.get(0).get("field"));
        assertEquals(328000L, ((Number) mismatches.get(0).get("actual")).longValue());
    }

    @Test
    void routeConfigDriftIsDetected() throws Exception {
        RouteProperties drifted = new RouteProperties();
        drifted.setFastLimitCents(100000); // 快赔阈值收紧 → GOLD-001 不再 FAST
        GoldRegressionService svc = new GoldRegressionService(new CoverageRuleEngine(), new ClaimCalculator(),
                new FraudScorer(), new ApprovalService(jdbc), drifted, jdbc, loadFixture());
        Map<String, Object> r = svc.run("TEST");
        assertTrue((Integer) r.get("failed") > 0);
    }

    private static List<GoldCase> loadFixture() throws Exception {
        try (InputStream in = GoldRegressionTest.class.getResourceAsStream("/gold-cases.json")) {
            return new ObjectMapper().findAndRegisterModules().readValue(in, new TypeReference<List<GoldCase>>() {
            });
        }
    }
}