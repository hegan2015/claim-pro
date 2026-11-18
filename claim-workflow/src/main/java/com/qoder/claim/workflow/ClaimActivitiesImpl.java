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
import com.qoder.claim.core.fraud.FraudScorer;
import com.qoder.claim.core.ledger.DoubleLedgerService;
import com.qoder.claim.core.rule.CoverageDecision;
import com.qoder.claim.core.rule.CoverageRuleEngine;
import com.qoder.claim.core.rule.PolicySnapshot;
import com.qoder.claim.mq.PaymentTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;
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
    private final FraudScorer fraud;
    private final EventPublisher events;
    private final AgentFacade agents;
    private final ObjectProvider<PaymentTransactionService> payTx;
    private final ObjectProvider<EvidenceStore> evidence;
    private final RouteProperties route;
    private final ObjectMapper json = new ObjectMapper();

    public ClaimActivitiesImpl(CaseRepository cases, MaterialRepository materials, ToolGateway tools,
                               CoverageRuleEngine coverage, ClaimCalculator calculator, ApprovalService approval,
                               DoubleLedgerService ledger, FraudScorer fraud, EventPublisher events,
                               AgentFacade agents, ObjectProvider<PaymentTransactionService> payTx,
                               ObjectProvider<EvidenceStore> evidence, RouteProperties route) {
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
        archive(caseNo, "FRAUD_SCORE", "{\"riskScore\":" + score + ",\"priorClaims\":" + prior + "}");
        return score;
    }

    @Override
    public AssessResult assess(String caseNo) {
        ClaimCase c = require(caseNo);
        String context = "{\"case\":" + toJson(c) + ",\"materials\":" + toJson(materials.list(caseNo))
                + ",\"policy\":" + tools.invoke("policy.query", caseNo, "{}") + "}";
        AssessResult r = agents.assess(caseNo, context);
        r.setCaseNo(caseNo);
        c.setEstimateCents(r.getEstimateCents());
        c.setRecognitionConfidence(0.9);
        cases.update(c);
        archive(caseNo, "ASSESS", toJson(r));
        return r;
    }

    @Override
    public Channel route(String caseNo, int riskScore, long estimateCents) {
        ClaimCase c = require(caseNo);
        Channel ch = ChannelRouter.route(riskScore, estimateCents, c.getRecognitionConfidence(), route);
        c.setChannel(ch);
        cases.update(c);
        archive(caseNo, "ROUTE", "{\"channel\":\"" + ch + "\",\"risk\":" + riskScore + "}");
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