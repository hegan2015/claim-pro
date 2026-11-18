package com.qoder.claim.workflow;

import com.qoder.claim.common.model.AssessResult;
import com.qoder.claim.common.model.CalcResult;
import com.qoder.claim.common.model.CaseStatus;
import com.qoder.claim.common.model.Channel;
import com.qoder.claim.common.model.ClaimInput;
import com.qoder.claim.core.rule.CoverageDecision;
import io.temporal.activity.ActivityInterface;

/** 案件流程 activity 集合。确定性步骤与 agent 步骤均在此收口，幂等键=案件号+步骤+批次。 */
@ActivityInterface
public interface ClaimActivities {

    void createCase(ClaimInput input);

    boolean materialsComplete(String caseNo);

    CoverageDecision coverageCheck(String caseNo);

    int fraudScore(String caseNo);

    /** agent 不可用时抛 AgentUnavailableException，workflow 降级 MANUAL 通道。 */
    AssessResult assess(String caseNo);

    Channel route(String caseNo, int riskScore, long estimateCents);

    CalcResult calculate(String caseNo);

    int requiredApprovers(String caseNo);

    void pay(String caseNo, long amountCents);

    void registerCore(String caseNo);

    void notify(String caseNo, String kind);

    void reject(String caseNo, String reason);

    void setStatus(String caseNo, CaseStatus status);

    void archive(String caseNo, String step, String outputJson);
}