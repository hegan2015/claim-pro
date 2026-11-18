package com.qoder.claim.infra.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.claim.common.port.ToolGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具网关（进程内实现，接口与生产一致）：案件级 ACL + 调用审计 + 写工具幂等。
 * 生产替换为独立网关服务（鉴权/限流/核心系统适配器），agent 侧 @Tool 代码不变。
 */
@Slf4j
@Component
public class ToolGatewayImpl implements ToolGateway {

    private static final Set<String> READ_TOOLS = Set.of(
            "policy.query", "parts.price", "history.count", "graph.flagged",
            "case.materials", "case.description", "liability.percent");
    private static final Set<String> WRITE_TOOLS = Set.of("core.register");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public ToolGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String invoke(String toolName, String caseNo, String argsJson) {
        if (!READ_TOOLS.contains(toolName) && !WRITE_TOOLS.contains(toolName)) {
            throw new SecurityException("未注册工具: " + toolName);
        }
        if (caseNo == null || caseNo.isBlank()) {
            throw new SecurityException("工具调用缺少案件上下文: " + toolName);
        }
        String result = dispatch(toolName, caseNo, argsJson);
        jdbc.update("INSERT INTO tool_audit(tool_name, case_no, args_json, result_json, ts) VALUES(?,?,?,?,?)",
                toolName, caseNo, argsJson, result.length() > 2000 ? result.substring(0, 2000) : result,
                System.currentTimeMillis());
        return result;
    }

    private String dispatch(String tool, String caseNo, String argsJson) {
        return switch (tool) {
            case "policy.query" -> policyQuery(caseNo);
            case "parts.price" -> partsPrice(argsJson);
            case "history.count" -> String.valueOf(historyCount(caseNo));
            case "graph.flagged" -> graphFlagged(caseNo);
            case "case.materials" -> caseMaterials(caseNo);
            case "case.description" -> caseDescription(caseNo);
            case "liability.percent" -> liabilityPercent(caseNo);
            case "core.register" -> coreRegister(caseNo);
            default -> throw new SecurityException("未注册工具: " + tool);
        };
    }

    private String policyQuery(String caseNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.* FROM policy_snapshot p JOIN claim_case c ON c.policy_no=p.policy_no WHERE c.case_no=?",
                caseNo);
        if (rows.isEmpty()) {
            throw new IllegalStateException("保单不存在: " + caseNo);
        }
        Map<String, Object> p = rows.get(0);
        return String.format("{\"policyNo\":\"%s\",\"effectiveDate\":\"%s\",\"expiryDate\":\"%s\"," +
                        "\"coverageTypes\":%s,\"limitCents\":%d,\"deductibleCents\":%d," +
                        "\"depreciationPercent\":%d,\"clauseVersion\":\"%s\"}",
                p.get("policy_no"), p.get("effective_date"), p.get("expiry_date"),
                toJsonArray(((String) p.get("coverage_types")).split(",")),
                ((Number) p.get("limit_cents")).longValue(),
                ((Number) p.get("deductible_cents")).longValue(),
                ((Number) p.get("depreciation_percent")).intValue(),
                p.get("clause_version"));
    }

    private String partsPrice(String argsJson) {
        try {
            Map<String, Object> args = json.readValue(argsJson, Map.class);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM part_price WHERE model_code=? AND part_name=?",
                    args.get("modelCode"), args.get("partName"));
            if (rows.isEmpty()) {
                return "{\"error\":\"价格库无此配件\",\"priceCents\":0,\"priceSourceId\":\"\"}";
            }
            Map<String, Object> r = rows.get(0);
            return String.format("{\"priceCents\":%d,\"priceSourceId\":\"%s\"}",
                    ((Number) r.get("price_cents")).longValue(), r.get("source_id"));
        } catch (Exception e) {
            throw new IllegalStateException("配件价格查询失败", e);
        }
    }

    private int historyCount(String caseNo) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claim_case WHERE policy_no=(SELECT policy_no FROM claim_case WHERE case_no=?) " +
                        "AND case_no<>? AND status IN ('SETTLED','CLOSED')", Long.class, caseNo, caseNo);
        return n == null ? 0 : n.intValue();
    }

    private String graphFlagged(String caseNo) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repair_shop_flag WHERE case_no=?", Long.class, caseNo);
        return "{\"flagged\":" + (n != null && n > 0) + "}";
    }

    private String caseMaterials(String caseNo) {
        try {
            return json.writeValueAsString(jdbc.queryForList(
                    "SELECT material_id, type, storage_key, confidence FROM claim_material WHERE case_no=?", caseNo));
        } catch (Exception e) {
            return "[]";
        }
    }

    private String caseDescription(String caseNo) {
        List<String> rows = jdbc.queryForList("SELECT description FROM claim_case WHERE case_no=?", String.class, caseNo);
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    private String liabilityPercent(String caseNo) {
        return String.valueOf(com.qoder.claim.common.rule.DescriptionRules.liabilityPercent(caseDescription(caseNo)));
    }

    private String coreRegister(String caseNo) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM core_register WHERE case_no=?", Long.class, caseNo);
        if (n != null && n > 0) {
            return "{\"registered\":true,\"idempotent\":true}";
        }
        jdbc.update("INSERT INTO core_register(case_no, result, ts) VALUES(?,?,?)",
                caseNo, "OK", System.currentTimeMillis());
        return "{\"registered\":true,\"idempotent\":false}";
    }

    private String toJsonArray(String[] items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(items[i].trim()).append('"');
        }
        return sb.append(']').toString();
    }
}