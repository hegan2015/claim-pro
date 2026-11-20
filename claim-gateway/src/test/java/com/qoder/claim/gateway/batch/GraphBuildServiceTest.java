package com.qoder.claim.gateway.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphBuildServiceTest {

    private JdbcTemplate jdbc;
    private GraphBuildService graph;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:graph" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE claim_case(case_no VARCHAR(64) PRIMARY KEY, policy_no VARCHAR(64), estimate_cents BIGINT, risk_score INT, status VARCHAR(32))");
        jdbc.execute("CREATE TABLE policy_snapshot(policy_no VARCHAR(64) PRIMARY KEY, clause_version VARCHAR(64))");
        jdbc.execute("CREATE TABLE repair_shop_flag(id BIGINT AUTO_INCREMENT PRIMARY KEY, case_no VARCHAR(64), shop_name VARCHAR(128), reason VARCHAR(256), source VARCHAR(16) DEFAULT 'MANUAL')");
        jdbc.execute("CREATE TABLE graph_case_shop(id BIGINT AUTO_INCREMENT PRIMARY KEY, case_no VARCHAR(64), shop_name VARCHAR(128), source VARCHAR(16) DEFAULT 'INTAKE', ts BIGINT, UNIQUE(case_no, shop_name))");
        jdbc.execute("CREATE TABLE graph_node(node_type VARCHAR(16), node_key VARCHAR(128), label VARCHAR(256), props_json VARCHAR(2000), ts BIGINT, PRIMARY KEY(node_type, node_key))");
        jdbc.execute("CREATE TABLE graph_edge(id BIGINT AUTO_INCREMENT PRIMARY KEY, src_type VARCHAR(16), src_key VARCHAR(128), dst_type VARCHAR(16), dst_key VARCHAR(128), edge_type VARCHAR(32), ts BIGINT)");
        jdbc.execute("CREATE TABLE graph_build_run(id BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT, trigger_by VARCHAR(16), node_count INT, edge_count INT, shop_count INT, new_flag_count INT, duration_ms BIGINT)");
        graph = new GraphBuildService(jdbc,
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.qoder.claim.infra.graph.NebulaGraphStore.class));

        jdbc.update("INSERT INTO policy_snapshot VALUES('P1','clause-2026-v1'),('P2','clause-2026-v1'),('P3','clause-2026-v1')");
        jdbc.update("INSERT INTO claim_case VALUES('C1','P1',6800000,85,'MANUAL')");
        jdbc.update("INSERT INTO claim_case VALUES('C2','P1',3200000,25,'APPROVING')");
        jdbc.update("INSERT INTO claim_case VALUES('C3','P2',100000,0,'REPORTED')");
        jdbc.update("INSERT INTO claim_case VALUES('C4','P2',200000,0,'REPORTED')");
        jdbc.update("INSERT INTO claim_case VALUES('C5','P3',6000000,25,'ASSESSING')");
        jdbc.update("INSERT INTO claim_case VALUES('C6','P3',7000000,25,'ASSESSING')");
        // shopX: C1 人工标记 + C2/C3 关联 → 3 案触发频次规则；shopY: 2 笔高额触发金额规则；shopZ: 单案不标记
        jdbc.update("INSERT INTO repair_shop_flag(case_no, shop_name, reason, source) VALUES('C1','shopX','人工调查标记','MANUAL')");
        jdbc.update("INSERT INTO graph_case_shop(case_no, shop_name, ts) VALUES('C1','shopX',1)");
        jdbc.update("INSERT INTO graph_case_shop(case_no, shop_name, ts) VALUES('C2','shopX',1)");
        jdbc.update("INSERT INTO graph_case_shop(case_no, shop_name, ts) VALUES('C3','shopX',1)");
        jdbc.update("INSERT INTO graph_case_shop(case_no, shop_name, ts) VALUES('C5','shopY',1)");
        jdbc.update("INSERT INTO graph_case_shop(case_no, shop_name, ts) VALUES('C6','shopY',1)");
        jdbc.update("INSERT INTO graph_case_shop(case_no, shop_name, ts) VALUES('C4','shopZ',1)");
    }

    @Test
    void buildCreatesGraphAndDerivesFlags() {
        Map<String, Object> r = graph.build("TEST");
        // 6 案件 + 3 保单 + 3 修理厂 = 12 节点；6 INSURED_BY + 6 REPAIRED_AT = 12 边
        assertEquals(12, r.get("nodeCount"));
        assertEquals(12, r.get("edgeCount"));
        assertEquals(3, r.get("shopCount"));
        // shopX: C2/C3 新增（C1 已有人工标记跳过）；shopY: C5/C6 新增
        assertEquals(4, r.get("newFlagCount"));
        assertEquals(4L, jdbc.queryForObject("SELECT COUNT(*) FROM repair_shop_flag WHERE source='GRAPH_BATCH'", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM repair_shop_flag WHERE source='MANUAL'", Long.class));
        // graph.flagged 工具语义：被标记案件可查到
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM repair_shop_flag WHERE case_no='C2'", Long.class) > 0);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM repair_shop_flag WHERE case_no='C4' AND source='GRAPH_BATCH'", Long.class));
    }

    @Test
    void rebuildIsIdempotentAndPreservesManualFlags() {
        graph.build("TEST");
        Map<String, Object> second = graph.build("TEST");
        assertEquals(4, second.get("newFlagCount"));
        assertEquals(4L, jdbc.queryForObject("SELECT COUNT(*) FROM repair_shop_flag WHERE source='GRAPH_BATCH'", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM repair_shop_flag WHERE source='MANUAL'", Long.class));
        assertEquals(12L, jdbc.queryForObject("SELECT COUNT(*) FROM graph_node", Long.class));
        assertEquals(12L, jdbc.queryForObject("SELECT COUNT(*) FROM graph_edge", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM graph_build_run", Long.class));
    }

    @Test
    void summaryReflectsShopRisk() {
        graph.build("TEST");
        Map<String, Object> s = graph.summary();
        assertNotNull(s.get("latestRun"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shops = (List<Map<String, Object>>) s.get("shops");
        assertEquals(3, shops.size());
        Map<String, Object> x = shops.stream().filter(m -> "shopX".equals(m.get("shopName"))).findFirst().orElseThrow();
        Map<String, Object> y = shops.stream().filter(m -> "shopY".equals(m.get("shopName"))).findFirst().orElseThrow();
        Map<String, Object> z = shops.stream().filter(m -> "shopZ".equals(m.get("shopName"))).findFirst().orElseThrow();
        assertEquals(true, x.get("flagged"));
        assertEquals(3, x.get("caseCount"));
        assertEquals(1, x.get("highAmountCount"));
        assertEquals(true, y.get("flagged"));
        assertEquals(2, y.get("highAmountCount"));
        assertEquals(false, z.get("flagged"));
    }

    @Test
    void neighbourhoodIncludesSharedShopSiblings() {
        graph.build("TEST");
        Map<String, Object> n = graph.neighbourhood("C2");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) n.get("nodes");
        List<String> keys = nodes.stream().map(m -> String.valueOf(Rows.val(m, "node_key"))).toList();
        assertTrue(keys.contains("C2"));
        assertTrue(keys.contains("C1")); // 同厂兄弟案件
        assertTrue(keys.contains("C3"));
        assertTrue(keys.contains("P1"));
        assertTrue(keys.contains("shopX"));
        assertFalse(keys.contains("C4"));
    }
}