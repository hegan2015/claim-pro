package com.qoder.claim.workflow;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * 灰度发布决策：案件号 CRC32 哈希分桶（确定性，同案多次判定一致）+ 机构/险种白名单。
 * 候选阈值仅作用于 CANARY 组，BASELINE 组与金标回归不受影响。配置可运行时调整（POST /api/canary/config）。
 */
@Service
public class CanaryService {

    private volatile CanaryProperties config;

    public CanaryService(CanaryProperties config) {
        this.config = config;
    }

    public Map<String, Object> config() {
        CanaryProperties c = config;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.isEnabled());
        m.put("percent", c.getPercent());
        m.put("orgs", c.getOrgs());
        m.put("insuranceTypes", c.getInsuranceTypes());
        m.put("candidateFastLimitCents", c.getCandidateFastLimitCents());
        m.put("candidateStandardLimitCents", c.getCandidateStandardLimitCents());
        m.put("candidateRiskLow", c.getCandidateRiskLow());
        m.put("candidateRiskHigh", c.getCandidateRiskHigh());
        m.put("candidateConfidenceMin", c.getCandidateConfidenceMin());
        return m;
    }

    public synchronized void update(Map<String, Object> body) {
        CanaryProperties c = config;
        if (body.containsKey("enabled")) {
            c.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        }
        if (body.containsKey("percent")) {
            c.setPercent(Math.max(0, Math.min(100, Integer.parseInt(String.valueOf(body.get("percent"))))));
        }
        if (body.containsKey("orgs")) {
            c.setOrgs(String.valueOf(body.get("orgs")));
        }
        if (body.containsKey("insuranceTypes")) {
            c.setInsuranceTypes(String.valueOf(body.get("insuranceTypes")));
        }
        if (body.containsKey("candidateFastLimitCents")) {
            c.setCandidateFastLimitCents(Long.parseLong(String.valueOf(body.get("candidateFastLimitCents"))));
        }
        if (body.containsKey("candidateRiskLow")) {
            c.setCandidateRiskLow(Integer.parseInt(String.valueOf(body.get("candidateRiskLow"))));
        }
        if (body.containsKey("candidateRiskHigh")) {
            c.setCandidateRiskHigh(Integer.parseInt(String.valueOf(body.get("candidateRiskHigh"))));
        }
    }

    /** 返回 CANARY / BASELINE。 */
    public String decideGroup(String caseNo, String org, String insuranceType) {
        CanaryProperties c = config;
        if (!c.isEnabled() || c.getPercent() <= 0) {
            return "BASELINE";
        }
        if (!matches(c.getOrgs(), org) || !matches(c.getInsuranceTypes(), insuranceType)) {
            return "BASELINE";
        }
        CRC32 crc = new CRC32();
        crc.update(caseNo.getBytes(StandardCharsets.UTF_8));
        return (crc.getValue() % 100) < c.getPercent() ? "CANARY" : "BASELINE";
    }

    private static boolean matches(String whitelist, String value) {
        if (whitelist == null || whitelist.isBlank()) {
            return true;
        }
        return Arrays.stream(whitelist.split(",")).map(String::trim).anyMatch(v -> v.equals(value));
    }

    /** 候选阈值合并基线（null 字段沿用基线）。 */
    public RouteProperties candidateRoute(RouteProperties baseline) {
        CanaryProperties c = config;
        RouteProperties r = new RouteProperties();
        r.setFastLimitCents(c.getCandidateFastLimitCents() != null ? c.getCandidateFastLimitCents() : baseline.getFastLimitCents());
        r.setStandardLimitCents(c.getCandidateStandardLimitCents() != null ? c.getCandidateStandardLimitCents() : baseline.getStandardLimitCents());
        r.setRiskLow(c.getCandidateRiskLow() != null ? c.getCandidateRiskLow() : baseline.getRiskLow());
        r.setRiskHigh(c.getCandidateRiskHigh() != null ? c.getCandidateRiskHigh() : baseline.getRiskHigh());
        r.setConfidenceMin(c.getCandidateConfidenceMin() != null ? c.getCandidateConfidenceMin() : baseline.getConfidenceMin());
        return r;
    }
}