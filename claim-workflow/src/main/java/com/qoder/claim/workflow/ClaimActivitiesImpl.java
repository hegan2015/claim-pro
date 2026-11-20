package com.qoder.claim.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.claim.agents.AgentFacade;
import com.qoder.claim.common.event.DecisionEvent;
import com.qoder.claim.common.model.AssessResult;
import com.qoder.claim.common.model.CalcResult;
import com.qoder.claim.common.model.CaseStatus;
import com.qoder.claim.common.model.Channel;
import com.qoder.claim.common.model.ClaimCase;
import com.qoder.claim.common.model.ClaimInput;
import com.qoder.claim.common.model.Material;
import com.qoder.claim.common.port.CaseRepository;
import com.qoder.claim.common.port.EventPublisher;
import com.qoder.claim.common.port.EvidenceStore;
import com.qoder.claim.common.port.MaterialRepository;
import com.qoder.claim.common.port.ToolGateway;
import com.qoder.claim.core.approval.ApprovalService;
import com.qoder.claim.core.calc.ClaimCalculator;
import com.qoder.claim.core.fraud.FraudScoringStrategy;
import com.qoder.claim.core.ledger.DoubleLedgerService;
import com.qoder.claim.core.rule.CoverageDecision;
import com.qoder.claim.core.rule.CoverageRuleEngine;
import com.qoder.claim.core.rule.PolicySnapshot;
import com.qoder.claim.mq.PaymentTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** activity 实现：确定性步骤直连核心域，agent 步骤经 AgentFacade，事件经 EventPublisher。 */
@Slf4j
@Service
public class ClaimActivitiesImpl implements ClaimActivities {

    private final CaseRepository cases;
    private final MaterialRepository materials;
    private final ToolGateway tools;
    private final CoverageRuleEngine coverage;
    private final ClaimCalculator calculator;
    private final ApprovalService approval;
    private final DoubleLedgerService ledger;
    private final FraudScoringStrategy fraud;
    private final EventPublisher events;
    private final AgentFacade agents;
    private final ObjectProvider<PaymentTransactionService> payTx;
    private final ObjectProvider<EvidenceStore> evidence;
    private final RouteProperties route;
    private final AssessGateProperties gate;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.qoder.claim.agents.VisionAssessService vision;
    private final ObjectProvider<com.qoder.claim.common.port.MaterialFileStore> fileStore;
    private final ObjectProvider<com.qoder.claim.infra.graph.NebulaGraphStore> nebula;
    private final CanaryService canary;
    private final ObjectMapper json = new ObjectMapper();

    public ClaimActivitiesImpl(CaseRepository cases, MaterialRepository materials, ToolGateway tools,
                               CoverageRuleEngine coverage, ClaimCalculator calculator, ApprovalService approval,
                               DoubleLedgerService ledger, FraudScoringStrategy fraud, EventPublisher events,
                               AgentFacade agents, ObjectProvider<PaymentTransactionService> payTx,
                               ObjectProvider<EvidenceStore> evidence, RouteProperties route, AssessGateProperties gate,
                               org.springframework.jdbc.core.JdbcTemplate jdbc,
                               com.qoder.claim.agents.VisionAssessService vision,
                               ObjectProvider<com.qoder.claim.common.port.MaterialFileStore> fileStore,
                               ObjectProvider<com.qoder.claim.infra.graph.NebulaGraphStore> nebula,
                               CanaryService canary) {
        this.cases = cases;
        this.materials = materials;
        this.tools = tools;
        this.coverage = coverage;
        this.calculator = calculator;
        this.approval = approval;
        this.ledger = ledger;
        this.fraud = fraud;
        this.events = events;
        this.agents = agents;
        this.payTx = payTx;
        this.evidence = evidence;
        this.route = route;
        this.gate = gate;
        this.jdbc = jdbc;
        this.vision = vision;
        this.fileStore = fileStore;
        this.nebula = nebula;
        this.canary = canary;
        json.findAndRegisterModules();
    }

    @Override
    public void createCase(ClaimInput input) {
        // 幂等：报案入口已同步建案（避免 API 返回前查不到行），Temporal activity 重试也安全
        if (cases.find(input.getCaseNo()).isPresent()) {
            return;
        }
        ClaimCase c = new ClaimCase();
        c.setCaseNo(input.getCaseNo());
        c.setPolicyNo(input.getPolicyNo());
        c.setOrg(input.getOrg());
        c.setInsuranceType(input.getInsuranceType());
        c.setAccidentDate(input.getAccidentDate());
        c.setAccidentType(input.getAccidentType());
        c.setStatus(CaseStatus.REPORTED);
        c.setDescription(input.getDescription());
        c.setCreatedAt(System.currentTimeMillis());
        c.setUpdatedAt(c.getCreatedAt());
        cases.save(c);
        archive(input.getCaseNo(), "REPORT", input.getDescription());
        // NebulaGraph 实时增量（可选增强，失败不阻断建案）
        com.qoder.claim.infra.graph.NebulaGraphStore ng = nebula.getIfAvailable();
        if (ng != null) {
            try {
                ng.upsertCase(input.getCaseNo(), input.getPolicyNo());
            } catch (Exception e) {
                log.warn("NebulaGraph 增量写入失败（不影响建案）case={}: {}", input.getCaseNo(), e.getMessage());
            }
        }
    }

    @Override
    public boolean materialsComplete(String caseNo) {
        List<Material> list = materials.list(caseNo);
        boolean report = list.stream().anyMatch(m -> "ACCIDENT_REPORT".equals(m.getType()));
        boolean photo = list.stream().anyMatch(m -> "DAMAGE_PHOTO".equals(m.getType()));
        return report && photo;
    }

    @Override
    public CoverageDecision coverageCheck(String caseNo) {
        ClaimCase c = require(caseNo);
        PolicySnapshot policy = policy(caseNo);
        String desc = tools.invoke("case.description", caseNo, "{}");
        CoverageDecision d = coverage.evaluate(policy, c.getAccidentDate(), c.getAccidentType(),
                com.qoder.claim.common.rule.DescriptionRules.drunkDriving(desc),
                com.qoder.claim.common.rule.DescriptionRules.unlicensed(desc),
                com.qoder.claim.common.rule.DescriptionRules.intentional(desc));
        archive(caseNo, "COVERAGE", toJson(d));
        return d;
    }

    @Override
    public int fraudScore(String caseNo) {
        ClaimCase c = require(caseNo);
        int prior = Integer.parseInt(tools.invoke("history.count", caseNo, "{}").replaceAll("[^0-9]", ""));
        boolean flagged = tools.invoke("graph.flagged", caseNo, "{}").contains("true");
        boolean lateNight = LocalTime.ofInstant(java.time.Instant.ofEpochMilli(c.getCreatedAt()),
                java.time.ZoneId.systemDefault()).getHour() < 6;
        int score = fraud.score(c.getEstimateCents(), prior, flagged, lateNight);
        c.setRiskScore(score);
        cases.update(c);
        archive(caseNo, "FRAUD_SCORE", "{\"riskScore\":" + score + ",\"priorClaims\":" + prior + ",\"strategy\":\"" + fraud.name() + "\"}");
        return score;
    }

    @Override
    public AssessResult assess(String caseNo) {
        ClaimCase c = require(caseNo);
        AssessResult r = visionAssess(c); // F2 视觉优先（真实置信度）
        if (r == null) {
            String context = "{\"case\":" + toJson(c) + ",\"materials\":" + toJson(materials.list(caseNo))
                    + ",\"policy\":" + tools.invoke("policy.query", caseNo, "{}") + "}";
            r = agents.assess(caseNo, context); // 文本定损回退（P0 置信度固定 0.9）
            r.setConfidence(0.9);
        }
        r.setCaseNo(caseNo);
        c.setEstimateCents(r.getEstimateCents());
        c.setRecognitionConfidence(r.getConfidence());
        cases.update(c);
        archive(caseNo, "ASSESS", toJson(r));
        return r;
    }

    /** 视觉定损：照片→部件识别→价格库定价（价格仍确定性）。不可用/异常返回 null 回退文本。 */
    private AssessResult visionAssess(ClaimCase c) {
        String modelCode = com.qoder.claim.common.rule.DescriptionRules.modelCode(c.getDescription());
        com.qoder.claim.common.port.MaterialFileStore fs = fileStore.getIfAvailable();
        if (modelCode == null || fs == null) {
            return null;
        }
        List<Material> photos = materials.list(c.getCaseNo()).stream()
                .filter(m -> "DAMAGE_PHOTO".equals(m.getType()) && m.getSizeBytes() != null && m.getSizeBytes() > 0)
                .limit(3).toList();
        if (photos.isEmpty()) {
            return null;
        }
        try {
            List<String> urls = new ArrayList<>();
            for (Material m : photos) {
                byte[] bytes = fs.get(m.getStorageKey());
                String mime = m.getContentType() == null ? "image/jpeg" : m.getContentType();
                urls.add("data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes));
            }
            String out = vision.identify(c.getDescription(), urls);
            var node = json.readTree(extractJson(out));
            AssessResult r = new AssessResult();
            long sum = 0;
            for (var it : node.path("items")) {
                String partName = it.path("partName").asText("");
                if (partName.isBlank()) {
                    continue;
                }
                var pj = json.readTree(tools.invoke("parts.price", c.getCaseNo(),
                        "{\"modelCode\":\"" + modelCode + "\",\"partName\":\"" + partName + "\"}"));
                long price = pj.path("priceCents").asLong(0);
                if (price <= 0) {
                    continue; // 价格库无此配件不得计入估价
                }
                AssessResult.Item item = new AssessResult.Item();
                item.setPartName(partName);
                item.setAction(it.path("action").asText("REPLACE"));
                item.setPriceCents(price);
                item.setPriceSourceId(pj.path("priceSourceId").asText(""));
                r.getItems().add(item);
                sum += price;
            }
            photos.forEach(m -> r.getEvidenceRefs().add(m.getMaterialId()));
            r.setEstimateCents(sum);
            r.setConfidence(Math.max(0, Math.min(1, node.path("confidence").asDouble(0))));
            archive(c.getCaseNo(), "ASSESS_VISION", out);
            return r;
        } catch (com.qoder.claim.agents.AgentUnavailableException e) {
            archive(c.getCaseNo(), "ASSESS_VISION", "{\"degraded\":\"视觉端点不可用，回退文本定损\"}");
            return null;
        } catch (Exception e) {
            log.warn("视觉定损异常，回退文本定损 case={}: {}", c.getCaseNo(), e.getMessage());
            return null;
        }
    }

    @Override
    public String assessGate(String caseNo) {
        ClaimCase c = require(caseNo);
        String reason = AssessGate.reason(c.getEstimateCents(),
                com.qoder.claim.common.rule.DescriptionRules.claimedAmountCents(c.getDescription()),
                c.getRecognitionConfidence(), gate, route);
        if (reason != null) {
            archive(caseNo, "ASSESS_GATE", "{\"manual\":true,\"reason\":\"" + reason + "\"}");
        }
        return reason;
    }

    @Override
    public Channel route(String caseNo, int riskScore, long estimateCents) {
        ClaimCase c = require(caseNo);
        String group = canary.decideGroup(caseNo, c.getOrg(), c.getInsuranceType());
        RouteProperties effective = "CANARY".equals(group) ? canary.candidateRoute(route) : route;
        Channel ch = ChannelRouter.route(riskScore, estimateCents, c.getRecognitionConfidence(), effective);
        c.setChannel(ch);
        cases.update(c);
        archive(caseNo, "ROUTE", "{\"channel\":\"" + ch + "\",\"risk\":" + riskScore + ",\"canaryGroup\":\"" + group + "\"}");
        return ch;
    }

    @Override
    public CalcResult calculate(String caseNo) {
        ClaimCase c = require(caseNo);
        PolicySnapshot policy = policy(caseNo);
        int liability = Integer.parseInt(tools.invoke("liability.percent", caseNo, "{}").replaceAll("[^0-9]", ""));
        CalcResult r = calculator.calculate(caseNo, c.getEstimateCents(), policy.getDeductibleCents(),
                policy.getDepreciationPercent(), policy.getLimitCents(), liability);
        c.setPayableCents(r.getPayableCents());
        cases.update(c);
        archive(caseNo, "CALCULATE", toJson(r));
        return r;
    }

    @Override
    public void calcDualRun(String caseNo) {
        try {
            ClaimCase c = require(caseNo);
            PolicySnapshot policy = policy(caseNo);
            int liability = Integer.parseInt(tools.invoke("liability.percent", caseNo, "{}").replaceAll("[^0-9]", ""));
            CalcResult calc = calculator.calculate(caseNo, c.getEstimateCents(), policy.getDeductibleCents(),
                    policy.getDepreciationPercent(), policy.getLimitCents(), liability);
            String inputs = String.format("{\"estimateCents\":%d,\"deductibleCents\":%d,\"depreciationPercent\":%d,\"limitCents\":%d,\"liabilityPercent\":%d}",
                    c.getEstimateCents(), policy.getDeductibleCents(), policy.getDepreciationPercent(), policy.getLimitCents(), liability);
            String draftJson = agents.calcDraft(inputs);
            long draftPayable = json.readTree(extractJson(draftJson)).path("payableCents").asLong(-1);
            long diff = draftPayable - calc.getPayableCents();
            boolean match = draftPayable >= 0 && diff == 0;
            jdbc.update("INSERT INTO calc_dual_run(case_no, calc_payable_cents, draft_payable_cents, diff_cents, is_match, draft_json, ts) VALUES(?,?,?,?,?,?,?)",
                    caseNo, calc.getPayableCents(), draftPayable, diff, match ? 1 : 0, draftJson, System.currentTimeMillis());
            archive(caseNo, "CALC_DUAL_RUN", String.format("{\"calc\":%d,\"draft\":%d,\"diff\":%d,\"match\":%s}",
                    calc.getPayableCents(), draftPayable, diff, match));
        } catch (com.qoder.claim.agents.AgentUnavailableException e) {
            archive(caseNo, "CALC_DUAL_RUN", "{\"degraded\":\"模型不可用，计算器单跑\"}");
        } catch (Exception e) {
            log.warn("理算双跑异常（不阻塞主链路）case={}: {}", caseNo, e.getMessage());
            archive(caseNo, "CALC_DUAL_RUN", "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @Override
    public String reviewGate(String caseNo) {
        ClaimCase c = require(caseNo);
        // 独立卷宗：只含原始事实与确定性结论，不含 F5 定损推理过程（R2 上下文隔离）
        String dossier = "{\"case\":{\"caseNo\":\"" + caseNo + "\",\"accidentType\":\"" + c.getAccidentType()
                + "\",\"accidentDate\":\"" + c.getAccidentDate() + "\",\"description\":" + toJson(c.getDescription())
                + ",\"estimateCents\":" + c.getEstimateCents() + ",\"payableCents\":" + c.getPayableCents()
                + ",\"riskScore\":" + c.getRiskScore() + ",\"channel\":\"" + c.getChannel() + "\"}"
                + ",\"materials\":" + toJson(materials.list(caseNo))
                + ",\"coverage\":" + latestOutput(caseNo, "COVERAGE")
                + ",\"calculation\":" + latestOutput(caseNo, "CALCULATE") + "}";
        String out;
        try {
            out = agents.review(caseNo, dossier);
        } catch (com.qoder.claim.agents.AgentUnavailableException e) {
            archive(caseNo, "REVIEW", "{\"degraded\":\"复核模型不可用，跳过门禁（确定性结论不受影响）\"}");
            return null;
        }
        archive(caseNo, "REVIEW", out);
        try {
            var node = json.readTree(extractJson(out));
            var challenges = node.path("challenges");
            boolean passed = node.path("passed").asBoolean(true);
            if (!passed || (challenges.isArray() && !challenges.isEmpty())) {
                StringBuilder sb = new StringBuilder("独立复核质疑，转人工深查: ");
                if (challenges.isArray()) {
                    for (var ch : challenges) {
                        sb.append(ch.path("point").asText("未指明")).append("; ");
                    }
                }
                return sb.toString();
            }
            return null;
        } catch (Exception e) {
            return "复核输出无法解析，保守转人工: " + e.getMessage();
        }
    }

    private String latestOutput(String caseNo, String step) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT output_json FROM claim_decision WHERE case_no=? AND step=? ORDER BY ts DESC, id DESC LIMIT 1",
                caseNo, step);
        if (rows.isEmpty()) {
            return "{}";
        }
        Object out = rows.get(0).get("output_json");
        if (out == null) {
            out = rows.get(0).get("OUTPUT_JSON");
        }
        String s = String.valueOf(out);
        return s.startsWith("{") ? s : toJson(s);
    }

    /** LLM 输出可能带 markdown 围栏，截取首个 { 到末尾 } 之间的 JSON。 */
    private static String extractJson(String s) {
        if (s == null) {
            return "{}";
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        return a >= 0 && b > a ? s.substring(a, b + 1) : "{}";
    }

    @Override
    public int requiredApprovers(String caseNo) {
        ClaimCase c = require(caseNo);
        return approval.requiredApprovers(c.getPayableCents(), c.getChannel());
    }

    @Override
    public void pay(String caseNo, long amountCents) {
        String idemKey = com.qoder.claim.common.IdempotencyKeys.pay(caseNo, 1);
        PaymentTransactionService tx = payTx.getIfAvailable();
        if (tx != null) {
            try {
                tx.sendPayTransaction(idemKey, caseNo, 1, amountCents, "BANK_DIRECT");
            } catch (Exception e) {
                throw new IllegalStateException("支付事务消息发送失败", e);
            }
        } else {
            ledger.pay(caseNo, 1, amountCents, "BANK_DIRECT");
        }
        ClaimCase c = require(caseNo);
        c.setPayableCents(amountCents); // 人工通道金额以最终决策为准
        cases.update(c);
        archive(caseNo, "PAY", "{\"amountCents\":" + amountCents + ",\"idemKey\":\"" + idemKey + "\"}");
    }

    @Override
    public void registerCore(String caseNo) {
        String result = tools.invoke("core.register", caseNo, "{}");
        archive(caseNo, "CORE_REGISTER", result);
    }

    @Override
    public void notify(String caseNo, String kind) {
        ClaimCase c = require(caseNo);
        events.publishNotify(caseNo, kind, toJson(c));
        archive(caseNo, "NOTIFY", "{\"kind\":\"" + kind + "\"}");
    }

    @Override
    public void reject(String caseNo, String reason) {
        ClaimCase c = require(caseNo);
        c.setStatus(CaseStatus.REJECTED);
        c.setRejectReason(reason);
        cases.update(c);
        archive(caseNo, "REJECT", reason);
    }

    @Override
    public void setStatus(String caseNo, CaseStatus status) {
        cases.updateStatus(caseNo, status);
    }

    @Override
    public void archive(String caseNo, String step, String outputJson) {
        DecisionEvent e = new DecisionEvent();
        e.setTraceId(UUID.randomUUID().toString());
        e.setCaseNo(caseNo);
        e.setStep(step);
        e.setActor("claim-pipeline");
        e.setOutputJson(outputJson);
        e.setTs(System.currentTimeMillis());
        events.publishDecision(e);
        EvidenceStore store = evidence.getIfAvailable();
        if (store != null) {
            store.snapshot(caseNo, step, Integer.toHexString(outputJson.hashCode()), outputJson,
                    "pipeline", "v1");
        }
    }

    private ClaimCase require(String caseNo) {
        return cases.find(caseNo).orElseThrow(() -> new IllegalStateException("案件不存在: " + caseNo));
    }

    private PolicySnapshot policy(String caseNo) {
        try {
            return json.readValue(tools.invoke("policy.query", caseNo, "{}"), PolicySnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("保单加载失败: " + caseNo, e);
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}