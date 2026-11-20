package com.qoder.claim.gateway.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 欺诈关联图谱 T+1 批处理：从关系数据全量重建「案件—保单—修理厂」图（节点/边落库），
 * 按确定性规则聚合修理厂风险并回写标记，供 graph.flagged 工具→欺诈评分→路由闭环消费。
 * 生产差异：图存储同步 NebulaGraph、XXL-Job 调度；标记规则与回写语义不变。
 */
@Slf4j
@Service
public class GraphBuildService {

    /** 高额案件阈值：估损 > ¥50,000（与欺诈评分大额档一致） */
    static final long HIGH_AMOUNT_CENTS = 5_000_000L;
    /** 同厂关联 ≥3 案 → 标记 */
    static final int FLAG_MIN_CASES = 3;
    /** 同厂高额案件 ≥2 案 → 标记 */
    static final int FLAG_MIN_HIGH_AMOUNT = 2;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final org.springframework.beans.factory.ObjectProvider<com.qoder.claim.infra.graph.NebulaGraphStore> nebula;

    public GraphBuildService(JdbcTemplate jdbc,
                             org.springframework.beans.factory.ObjectProvider<com.qoder.claim.infra.graph.NebulaGraphStore> nebula) {
        this.jdbc = jdbc;
        this.nebula = nebula;
    }

    public Map<String, Object> build(String triggerBy) {
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> cases = jdbc.queryForList(
                "SELECT case_no, policy_no, estimate_cents, risk_score, status FROM claim_case");
        List<Map<String, Object>> policies = jdbc.queryForList(
                "SELECT policy_no, clause_version FROM policy_snapshot");
        List<Map<String, Object>> links = jdbc.queryForList(
                "SELECT case_no, shop_name FROM graph_case_shop");
        List<Map<String, Object>> flags = jdbc.queryForList(
                "SELECT case_no, shop_name, reason, source FROM repair_shop_flag");

        Map<String, Long> estimateByCase = new HashMap<>();
        for (Map<String, Object> c : cases) {
            estimateByCase.put(Rows.str(c, "case_no"), Rows.num(c, "estimate_cents"));
        }

        // T+1 全量重建：派生图表清空重算，源数据只读
        jdbc.update("DELETE FROM graph_edge");
        jdbc.update("DELETE FROM graph_node");

        int nodes = 0;
        int edges = 0;
        for (Map<String, Object> c : cases) {
            String caseNo = Rows.str(c, "case_no");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("status", Rows.str(c, "status"));
            props.put("estimateCents", Rows.num(c, "estimate_cents"));
            props.put("riskScore", Rows.num(c, "risk_score"));
            nodes += insertNode("CASE", caseNo, caseNo, toJson(props), t0);
            String policyNo = Rows.str(c, "policy_no");
            if (policyNo != null) {
                edges += insertEdge("CASE", caseNo, "POLICY", policyNo, "INSURED_BY", t0);
            }
        }
        for (Map<String, Object> p : policies) {
            String policyNo = Rows.str(p, "policy_no");
            nodes += insertNode("POLICY", policyNo, policyNo,
                    toJson(Map.of("clauseVersion", String.valueOf(Rows.str(p, "clause_version")))), t0);
        }

        Map<String, ShopAgg> shops = aggregate(links, estimateByCase);
        applyFlags(shops, flags);
        Set<String> seenLinks = new HashSet<>();
        for (Map<String, Object> l : links) {
            String caseNo = Rows.str(l, "case_no");
            String shop = Rows.str(l, "shop_name");
            if (caseNo == null || shop == null) {
                continue;
            }
            if (seenLinks.add(caseNo + "|" + shop)) {
                edges += insertEdge("CASE", caseNo, "SHOP", shop, "REPAIRED_AT", t0);
            }
        }
        for (String shop : shops.keySet()) {
            nodes += insertNode("SHOP", shop, shop, "{}", t0);
        }

        // 批处理标记回写：只清 GRAPH_BATCH，人工登记（MANUAL）永久保留
        jdbc.update("DELETE FROM repair_shop_flag WHERE source='GRAPH_BATCH'");
        int newFlags = 0;
        for (Map.Entry<String, ShopAgg> e : shops.entrySet()) {
            ShopAgg a = e.getValue();
            if (!ruleFlagged(a)) {
                continue;
            }
            String reason = "图谱批处理: 同厂关联" + a.cases.size() + "案(高额" + a.highAmountCases + "案)";
            for (String caseNo : a.cases) {
                Long manual = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM repair_shop_flag WHERE case_no=? AND shop_name=? AND source='MANUAL'",
                        Long.class, caseNo, e.getKey());
                if (manual != null && manual > 0) {
                    continue;
                }
                jdbc.update("INSERT INTO repair_shop_flag(case_no, shop_name, reason, source) VALUES(?,?,?, 'GRAPH_BATCH')",
                        caseNo, e.getKey(), reason);
                newFlags++;
            }
        }

        long duration = System.currentTimeMillis() - t0;
        jdbc.update("INSERT INTO graph_build_run(ts, trigger_by, node_count, edge_count, shop_count, new_flag_count, duration_ms) " +
                "VALUES(?,?,?,?,?,?,?)", t0, triggerBy, nodes, edges, shops.size(), newFlags, duration);
        log.info("图谱构建完成[{}]: nodes={} edges={} shops={} newFlags={} {}ms",
                triggerBy, nodes, edges, shops.size(), newFlags, duration);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", t0);
        out.put("triggerBy", triggerBy);
        out.put("nodeCount", nodes);
        out.put("edgeCount", edges);
        out.put("shopCount", shops.size());
        out.put("newFlagCount", newFlags);
        out.put("durationMs", duration);
        // NebulaGraph 实时图同步（可选增强，失败不阻断 T+1 关系库基线）
        com.qoder.claim.infra.graph.NebulaGraphStore store = nebula.getIfAvailable();
        if (store != null) {
            try {
                out.put("nebulaSync", store.syncFromDb(jdbc));
            } catch (Exception e) {
                log.warn("NebulaGraph 同步失败（关系库基线不受影响）: {}", e.getMessage());
                out.put("nebulaSync", Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        return out;
    }

    public Map<String, Object> summary() {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT * FROM graph_build_run ORDER BY id DESC LIMIT 1");
        List<Map<String, Object>> links = jdbc.queryForList(
                "SELECT case_no, shop_name FROM graph_case_shop");
        List<Map<String, Object>> flags = jdbc.queryForList(
                "SELECT case_no, shop_name, reason, source FROM repair_shop_flag");
        Map<String, Long> estimateByCase = new HashMap<>();
        jdbc.queryForList("SELECT case_no, estimate_cents FROM claim_case")
                .forEach(c -> estimateByCase.put(Rows.str(c, "case_no"), Rows.num(c, "estimate_cents")));
        Map<String, ShopAgg> shops = aggregate(links, estimateByCase);
        applyFlags(shops, flags);

        List<Map<String, Object>> shopRows = new ArrayList<>();
        for (Map.Entry<String, ShopAgg> e : shops.entrySet()) {
            ShopAgg a = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shopName", e.getKey());
            row.put("cases", new ArrayList<>(a.cases));
            row.put("caseCount", a.cases.size());
            row.put("highAmountCount", a.highAmountCases);
            row.put("totalEstimateCents", a.totalEstimateCents);
            row.put("flagged", ruleFlagged(a) || a.flagRows > 0);
            row.put("reasons", new ArrayList<>(a.reasons));
            shopRows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("latestRun", runs.isEmpty() ? null : runs.get(0));
        out.put("nodeCount", jdbc.queryForObject("SELECT COUNT(*) FROM graph_node", Long.class));
        out.put("edgeCount", jdbc.queryForObject("SELECT COUNT(*) FROM graph_edge", Long.class));
        out.put("manualFlagCount", jdbc.queryForObject("SELECT COUNT(*) FROM repair_shop_flag WHERE source='MANUAL'", Long.class));
        out.put("batchFlagCount", jdbc.queryForObject("SELECT COUNT(*) FROM repair_shop_flag WHERE source='GRAPH_BATCH'", Long.class));
        out.put("shops", shopRows);
        return out;
    }

    /** 案件邻域：自身 + 保单/修理厂一跳 + 同厂关联案件（欺诈调查视角）。 */
    public Map<String, Object> neighbourhood(String caseNo) {
        List<Map<String, Object>> edges = new ArrayList<>(jdbc.queryForList(
                "SELECT * FROM graph_edge WHERE src_type='CASE' AND src_key=?", caseNo));
        Set<String> shopKeys = new LinkedHashSet<>();
        for (Map<String, Object> e : edges) {
            if ("SHOP".equals(Rows.str(e, "dst_type"))) {
                shopKeys.add(Rows.str(e, "dst_key"));
            }
        }
        Set<String> caseKeys = new LinkedHashSet<>();
        caseKeys.add(caseNo);
        for (String shop : shopKeys) {
            for (Map<String, Object> e : jdbc.queryForList(
                    "SELECT * FROM graph_edge WHERE dst_type='SHOP' AND dst_key=? AND src_key<>?", shop, caseNo)) {
                edges.add(e);
                caseKeys.add(Rows.str(e, "src_key"));
            }
        }
        Set<String> nodeKeys = new LinkedHashSet<>(caseKeys);
        for (Map<String, Object> e : edges) {
            nodeKeys.add(Rows.str(e, "dst_type") + "|" + Rows.str(e, "dst_key"));
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String nk : nodeKeys) {
            int sep = nk.indexOf('|');
            String type = sep < 0 ? "CASE" : nk.substring(0, sep);
            String key = sep < 0 ? nk : nk.substring(sep + 1);
            nodes.addAll(jdbc.queryForList("SELECT * FROM graph_node WHERE node_type=? AND node_key=?", type, key));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("center", caseNo);
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    public List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT * FROM graph_build_run ORDER BY id DESC LIMIT 20");
    }

    private static boolean ruleFlagged(ShopAgg a) {
        return a.cases.size() >= FLAG_MIN_CASES || a.highAmountCases >= FLAG_MIN_HIGH_AMOUNT;
    }

    private Map<String, ShopAgg> aggregate(List<Map<String, Object>> links, Map<String, Long> estimateByCase) {
        Map<String, ShopAgg> shops = new LinkedHashMap<>();
        for (Map<String, Object> l : links) {
            String caseNo = Rows.str(l, "case_no");
            String shop = Rows.str(l, "shop_name");
            if (caseNo == null || shop == null) {
                continue;
            }
            ShopAgg a = shops.computeIfAbsent(shop, k -> new ShopAgg());
            if (a.cases.add(caseNo)) {
                long est = estimateByCase.getOrDefault(caseNo, 0L);
                a.totalEstimateCents += est;
                if (est > HIGH_AMOUNT_CENTS) {
                    a.highAmountCases++;
                }
            }
        }
        return shops;
    }

    /** 标记登记表按厂归集：flagRows 计 MANUAL/GRAPH_BATCH 行，reasons 去重保留。 */
    private static void applyFlags(Map<String, ShopAgg> shops, List<Map<String, Object>> flags) {
        for (Map<String, Object> f : flags) {
            ShopAgg a = shops.get(Rows.str(f, "shop_name"));
            if (a == null) {
                continue;
            }
            a.flagRows++;
            String reason = Rows.str(f, "reason");
            if (reason != null && !a.reasons.contains(reason)) {
                a.reasons.add(reason);
            }
        }
    }

    private int insertNode(String type, String key, String label, String propsJson, long ts) {
        return jdbc.update("INSERT INTO graph_node(node_type, node_key, label, props_json, ts) VALUES(?,?,?,?,?)",
                type, key, label, propsJson, ts);
    }

    private int insertEdge(String srcType, String srcKey, String dstType, String dstKey, String edgeType, long ts) {
        return jdbc.update("INSERT INTO graph_edge(src_type, src_key, dst_type, dst_key, edge_type, ts) VALUES(?,?,?,?,?,?)",
                srcType, srcKey, dstType, dstKey, edgeType, ts);
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("图谱节点属性序列化失败", e);
        }
    }

    private static final class ShopAgg {
        final TreeSet<String> cases = new TreeSet<>();
        final List<String> reasons = new ArrayList<>();
        int highAmountCases;
        long totalEstimateCents;
        int flagRows;
    }
}