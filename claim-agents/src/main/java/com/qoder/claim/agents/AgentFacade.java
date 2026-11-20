package com.qoder.claim.agents;

import com.qoder.claim.common.model.AssessResult;
import com.qoder.claim.llm.LlmGatewayException;
import com.qoder.claim.runtime.AgentFactory;
import com.qoder.claim.runtime.CaseContext;
import com.qoder.claim.agents.tools.AssessTools;
import com.qoder.claim.agents.tools.ReviewTools;
import org.springframework.stereotype.Service;

/**
 * agent 门面：可用性检查 + 案件上下文注入 + 异常归一。
 * 模型端点未配置/熔断 → AgentUnavailableException → workflow 降级 MANUAL（显式降级）。
 */
@Service
public class AgentFacade {

    private final AgentFactory factory;
    private final IntakeAgent intake;
    private final AssessAgent assess;
    private final CoverageExplainAgent coverageExplain;
    private final FraudReportAgent fraudReport;
    private final ReviewAgent review;
    private final NotifyAgent notify;
    private final CalcDraftAgent calcDraft;

    public AgentFacade(AgentFactory factory, AssessTools assessTools, ReviewTools reviewTools) {
        this.factory = factory;
        this.intake = factory.build(IntakeAgent.class, "intake");
        this.assess = factory.build(AssessAgent.class, "assess", assessTools);
        this.coverageExplain = factory.build(CoverageExplainAgent.class, "coverage-explain");
        this.fraudReport = factory.build(FraudReportAgent.class, "fraud-report");
        this.review = factory.build(ReviewAgent.class, "review", reviewTools);
        this.notify = factory.build(NotifyAgent.class, "notify");
        this.calcDraft = factory.build(CalcDraftAgent.class, "calc-draft");
    }

    public boolean available(String agentType) {
        return factory.available(agentType);
    }

    public String intakeStructure(String description) {
        return guard("intake", () -> intake.structure(description));
    }

    public AssessResult assess(String caseNo, String contextJson) {
        return guard("assess", () -> {
            CaseContext.set(caseNo);
            try {
                return assess.assess(contextJson);
            } finally {
                CaseContext.clear();
            }
        });
    }

    public String explainCoverage(String decisionJson) {
        return guard("coverage-explain", () -> coverageExplain.explain(decisionJson));
    }

    public String fraudReport(String signalsJson) {
        return guard("fraud-report", () -> fraudReport.report(signalsJson));
    }

    public String review(String caseNo, String dossierJson) {
        return guard("review", () -> {
            CaseContext.set(caseNo);
            try {
                return review.review(dossierJson);
            } finally {
                CaseContext.clear();
            }
        });
    }

    public String notifyReply(String stateAndQuestionJson) {
        return guard("notify", () -> notify.reply(stateAndQuestionJson));
    }

    public String calcDraft(String inputsJson) {
        return guard("calc-draft", () -> calcDraft.draft(inputsJson));
    }

    private <T> T guard(String agentType, java.util.function.Supplier<T> call) {
        if (!factory.available(agentType)) {
            throw new AgentUnavailableException(agentType, "端点未配置或熔断中");
        }
        try {
            return call.get();
        } catch (LlmGatewayException e) {
            throw new AgentUnavailableException(agentType, e);
        }
    }
}