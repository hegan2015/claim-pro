package com.qoder.claim.infra.graph;

import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * NebulaGraph 实时图存储适配器（可选增强）。T+1 关系库重建保留为对账基线，本适配器提供实时增量。
 * 懒连接：首次使用才建池/建 schema；任何异常向上抛，由调用方降级（不阻塞理赔主链路）。
 * 图模型：case_node/policy_node/shop_node 三类顶点；holds_policy/repaired_at/flagged_case 三类边。
 */
@Slf4j
public class NebulaGraphStore {

    private final NebulaProperties props;
    private volatile NebulaPool pool;
    private volatile boolean schemaReady;

    public NebulaGraphStore(NebulaProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.isEnabled();
    }

    private synchronized NebulaPool pool() throws Exception {
        if (pool == null) {
            List<HostAddress> addrs = new ArrayList<>();
            for (String h : props.getHosts().split(",")) {
                String[] hp = h.trim().split(":");
                addrs.add(new HostAddress(hp[0], Integer.parseInt(hp[1])));
            }
            NebulaPool p = new NebulaPool();
            NebulaPoolConfig cfg = new NebulaPoolConfig();
            cfg.setMaxConnSize(5);
            cfg.setTimeout(15000);
            if (!p.init(addrs, cfg)) {
                throw new IllegalStateException("NebulaGraph 连接池初始化失败: " + props.getHosts());
            }
            pool = p;
        }
        return pool;
    }

    private ResultSet exec(String ngql) throws Exception {
        Session session = pool().getSession(props.getUser(), props.getPassword(), true);
        try {
            ResultSet rs = session.execute(ngql);
            if (!rs.isSucceeded()) {
                throw new IllegalStateException("nGQL 失败[" + rs.getErrorMessage() + "]: " + ngql);
            }
            return rs;
        } finally {
            session.release();
        }
    }

    /** 建 space/tag/edge/index（幂等）。space 创建异步，USE 需等心跳，重试等待。 */
    public synchronized void ensureSchema() throws Exception {
        if (schemaReady) {
            return;
        }
        // Nebula 3.x 存储节点须显式注册（幂等，已注册会报 existed 忽略）
        try {
            exec("ADD HOSTS \"storaged0\":9779");
        } catch (Exception e) {
            log.debug("ADD HOSTS 跳过（可能已注册）: {}", e.getMessage());
        }
        exec("CREATE SPACE IF NOT EXISTS " + props.getSpace() + "(vid_type=FIXED_STRING(64), replica_factor=1)");
        Exception last = null;
        for (int i = 0; i < 15; i++) {
            try {
                exec("USE " + props.getSpace());
                last = null;
                break;
            } catch (Exception e) {
                last = e;
                Thread.sleep(2000);
            }
        }
        if (last != null) {
            throw last;
        }
        String space = props.getSpace();
        exec("USE " + space + "; CREATE TAG IF NOT EXISTS case_node(case_no string, status string, estimate_cents int, risk_score int)");
        exec("USE " + space + "; CREATE TAG IF NOT EXISTS policy_node(policy_no string)");
        exec("USE " + space + "; CREATE TAG IF NOT EXISTS shop_node(shop_name string, flagged bool)");
        exec("USE " + space + "; CREATE EDGE IF NOT EXISTS holds_policy()");
        exec("USE " + space + "; CREATE EDGE IF NOT EXISTS repaired_at()");
        exec("USE " + space + "; CREATE EDGE IF NOT EXISTS flagged_case()");
        exec("USE " + space + "; CREATE TAG INDEX IF NOT EXISTS idx_case ON case_node()");
        exec("USE " + space + "; CREATE TAG INDEX IF NOT EXISTS idx_shop ON shop_node()");
        // Nebula schema/索引经心跳异步传播（约 2 个心跳周期），等待生效后再写数据
        Thread.sleep(20000);
        // LOOKUP 依赖索引重建完成
        try {
            exec("USE " + space + "; REBUILD TAG INDEX idx_case");
            exec("USE " + space + "; REBUILD TAG INDEX idx_shop");
            Thread.sleep(8000);
        } catch (Exception e) {
            log.debug("索引重建跳过: {}", e.getMessage());
        }
        schemaReady = true;
        log.info("NebulaGraph schema 就绪 space={}", space);
    }

    /** 全量同步：从关系库（T+1 重建结果）灌入图，作为实时增量的对账基线。 */
    public Map<String, Object> syncFromDb(JdbcTemplate jdbc) throws Exception {
        ensureSchema();
        String space = props.getSpace();
        int cases = 0;
        int edges = 0;
        for (Map<String, Object> c : jdbc.queryForList("SELECT case_no, policy_no, status, estimate_cents, risk_score FROM claim_case")) {
            String caseNo = str(c.get("case_no"));
            String vid = "case:" + caseNo;
            exec("USE " + space + "; INSERT VERTEX case_node(case_no, status, estimate_cents, risk_score) VALUES \"" + vid + "\":(\""
                    + esc(caseNo) + "\",\"" + esc(str(c.get("status"))) + "\"," + num(c.get("estimate_cents")) + "," + num(c.get("risk_score")) + ")");
            cases++;
            String policyNo = str(c.get("policy_no"));
            if (!policyNo.isEmpty()) {
                exec("USE " + space + "; INSERT VERTEX policy_node(policy_no) VALUES \"policy:" + esc(policyNo) + "\":(\"" + esc(policyNo) + "\")");
                exec("USE " + space + "; INSERT EDGE holds_policy() VALUES \"" + vid + "\"->\"policy:" + esc(policyNo) + "\":()");
                edges++;
            }
        }
        for (Map<String, Object> s : jdbc.queryForList("SELECT case_no, shop_name FROM graph_case_shop")) {
            String caseNo = str(s.get("case_no"));
            String shop = str(s.get("shop_name"));
            if (shop.isEmpty()) {
                continue;
            }
            exec("USE " + space + "; INSERT VERTEX shop_node(shop_name, flagged) VALUES \"shop:" + esc(shop) + "\":(\"" + esc(shop) + "\",false)");
            exec("USE " + space + "; INSERT EDGE repaired_at() VALUES \"case:" + esc(caseNo) + "\"->\"shop:" + esc(shop) + "\":()");
            edges++;
        }
        for (Map<String, Object> f : jdbc.queryForList("SELECT case_no, shop_name FROM repair_shop_flag")) {
            String shop = str(f.get("shop_name"));
            String caseNo = str(f.get("case_no"));
            if (shop.isEmpty()) {
                continue;
            }
            exec("USE " + space + "; INSERT VERTEX shop_node(shop_name, flagged) VALUES \"shop:" + esc(shop) + "\":(\"" + esc(shop) + "\",true)");
            exec("USE " + space + "; INSERT EDGE flagged_case() VALUES \"shop:" + esc(shop) + "\"->\"case:" + esc(caseNo) + "\":()");
            edges++;
        }
        log.info("NebulaGraph 全量同步: cases={} edges={}", cases, edges);
        return Map.of("cases", cases, "edges", edges);
    }

    /** 实时增量：报案建案即写 case/policy 顶点与 holds_policy 边。 */
    public void upsertCase(String caseNo, String policyNo) throws Exception {
        ensureSchema();
        String space = props.getSpace();
        exec("USE " + space + "; INSERT VERTEX case_node(case_no, status, estimate_cents, risk_score) VALUES \"case:" + esc(caseNo) + "\":(\""
                + esc(caseNo) + "\",\"REPORTED\",0,0)");
        if (policyNo != null && !policyNo.isEmpty()) {
            exec("USE " + space + "; INSERT VERTEX policy_node(policy_no) VALUES \"policy:" + esc(policyNo) + "\":(\"" + esc(policyNo) + "\")");
            exec("USE " + space + "; INSERT EDGE holds_policy() VALUES \"case:" + esc(caseNo) + "\"->\"policy:" + esc(policyNo) + "\":()");
        }
    }

    public Map<String, Object> status() {
        if (!props.isEnabled()) {
            return Map.of("enabled", false);
        }
        try {
            ensureSchema();
            ResultSet rs = exec("USE " + props.getSpace() + "; LOOKUP ON case_node YIELD id(vertex) AS vid | YIELD COUNT(*) AS cnt");
            long caseCount = rs.isEmpty() ? 0 : rs.colValues("cnt").get(0).asLong();
            return Map.of("enabled", true, "healthy", true, "space", props.getSpace(), "caseVertices", caseCount);
        } catch (Exception e) {
            return Map.of("enabled", true, "healthy", false, "error", String.valueOf(e.getMessage()));
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}