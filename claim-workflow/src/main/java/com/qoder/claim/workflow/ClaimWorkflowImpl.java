package com.qoder.claim.workflow;

import com.qoder.claim.common.model.AssessResult;
import com.qoder.claim.common.model.CalcResult;
import com.qoder.claim.common.model.CaseStatus;
import com.qoder.claim.common.model.Channel;
import com.qoder.claim.common.model.ClaimInput;
import com.qoder.claim.common.model.HumanDecision;
import com.qoder.claim.core.rule.CoverageDecision;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 案件主流程：状态机走向完全由本类（确定性代码）决定，agent 只在 activity 内部发挥。
 * 人工节点=signal+durable await；模型故障=降级 MANUAL 通道，流程不停摆。
 */
public class ClaimWorkflowImpl implements ClaimWorkflow {

    private final ClaimActivities act = Workflow.newActivityStub(ClaimActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(ClaimQueues.CORE)
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    /** 定损走独立队列（LLM 长耗时，独立限流不饿死核心 activity）。 */
    private final ClaimActivities assessAct = Workflow.newActivityStub(ClaimActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(ClaimQueues.ASSESS)
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    /** 通知/沟通走受理队列。 */
    private final ClaimActivities notifyAct = Workflow.newActivityStub(ClaimActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(ClaimQueues.INTAKE)
                    .setStartToCloseTimeout(Duration.ofMinutes(3))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    /** 独立复核走隔离队列（只读工具，R2 物理隔离）。 */
    private final ClaimActivities reviewAct = Workflow.newActivityStub(ClaimActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(ClaimQueues.REVIEW)
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    private String status = "REPORTED";
    private String channel = "";
    private boolean materialsSignal;
    private boolean disputed;
    private final List<HumanDecision> decisions = new ArrayList<>();

    @Override
    public void run(ClaimInput input) {
        String caseNo = input.getCaseNo();
        act.createCase(input);

        while (!act.materialsComplete(caseNo)) {
            transition(caseNo, CaseStatus.MATERIALS_PENDING);
            Workflow.await(() -> materialsSignal || disputed);
            materialsSignal = false;
            if (disputed) {
                toManual(caseNo, "客户异议，补件阶段升级人工");
                return;
            }
        }

        transition(caseNo, CaseStatus.COVERAGE_CHECK);
        CoverageDecision cov = act.coverageCheck(caseNo);
        act.archive(caseNo, "COVERAGE",
                "covered=" + cov.isCovered() + " clauses=" + cov.getHitClauses() + " exclusions=" + cov.getExclusions());
        if (!cov.isCovered()) {
            transition(caseNo, CaseStatus.APPROVING);
            HumanDecision confirm = awaitDecision(caseNo, "REJECT_CONFIRM");
            if (confirm != null && confirm.isApproved()) {
                act.reject(caseNo, String.join(",", cov.getExclusions()));
                notifyAct.notify(caseNo, "REJECTED");
                status = "REJECTED";
            } else {
                toManual(caseNo, "拒赔人工确认未通过，转人工核赔");
            }
            return;
        }

        transition(caseNo, CaseStatus.ASSESSING);
        Promise<Integer> riskP = Async.function(act::fraudScore, caseNo);
        Promise<AssessResult> assessP = Async.function(assessAct::assess, caseNo);
        int risk;
        AssessResult assess;
        try {
            riskP.get();
            assess = assessP.get();
            risk = act.fraudScore(caseNo); // 定损写回后以最终估损重评（首跑并行时金额特征尚未就绪）
        } catch (ActivityFailure e) {
            toManual(caseNo, "模型/识别服务不可用，降级人工通道: " + e.getCause());
            return;
        }

        String gateReason = act.assessGate(caseNo);
        if (gateReason != null) {
            toManual(caseNo, gateReason);
            return;
        }

        Channel ch = act.route(caseNo, risk, assess.getEstimateCents());
        channel = ch.name();
        transition(caseNo, CaseStatus.ROUTED);
        if (ch == Channel.MANUAL || ch == Channel.INVESTIGATE) {
            toManual(caseNo, ch == Channel.INVESTIGATE ? "高风险/大额，SIU 人工调查" : "分级路由进入人工通道");
            return;
        }
        if (disputed) {
            toManual(caseNo, "客户异议升级人工");
            return;
        }

        CalcResult calc = act.calculate(caseNo);
        transition(caseNo, CaseStatus.CALCULATED);
        act.archive(caseNo, "CALCULATE", calc.getBreakdown());

        act.calcDualRun(caseNo); // F6 双跑比对（降级不阻塞）
        String reviewReason = reviewAct.reviewGate(caseNo); // F7 强制门禁
        if (reviewReason != null) {
            toManual(caseNo, reviewReason);
            return;
        }

        int need = act.requiredApprovers(caseNo);
        if (need > 0) {
            transition(caseNo, CaseStatus.APPROVING);
            long approved = 0;
            while (approved < need) {
                HumanDecision d = awaitDecision(caseNo, "APPROVE");
                if (d == null) {
                    toManual(caseNo, "审批阶段客户异议");
                    return;
                }
                if (!d.isApproved()) {
                    act.reject(caseNo, "审批驳回: " + d.getComment());
                    notifyAct.notify(caseNo, "REJECTED");
                    status = "REJECTED";
                    return;
                }
                approved++;
            }
        }

        transition(caseNo, CaseStatus.PAYING);
        act.pay(caseNo, calc.getPayableCents());
        act.registerCore(caseNo);
        transition(caseNo, CaseStatus.SETTLED);
        notifyAct.notify(caseNo, "SETTLED");
        transition(caseNo, CaseStatus.CLOSED);
    }

    private void toManual(String caseNo, String reason) {
        channel = Channel.MANUAL.name();
        transition(caseNo, CaseStatus.MANUAL);
        act.archive(caseNo, "MANUAL", reason);
        notifyAct.notify(caseNo, "MANUAL");
        HumanDecision d = awaitDecision(caseNo, "MANUAL_HANDLE");
        if (d != null && d.isApproved() && d.getAmountCents() > 0) {
            transition(caseNo, CaseStatus.PAYING);
            act.pay(caseNo, d.getAmountCents());
            act.registerCore(caseNo);
            transition(caseNo, CaseStatus.SETTLED);
            notifyAct.notify(caseNo, "SETTLED");
            transition(caseNo, CaseStatus.CLOSED);
        } else {
            act.reject(caseNo, "人工处理关闭: " + (d == null ? "超时" : d.getComment()));
            status = "REJECTED";
        }
    }

    private HumanDecision awaitDecision(String caseNo, String step) {
        Workflow.await(() -> disputed || decisions.stream().anyMatch(d -> step.equals(d.getStep())));
        if (disputed) {
            return null;
        }
        return decisions.stream().filter(d -> step.equals(d.getStep())).reduce((a, b) -> b).orElse(null);
    }

    private void transition(String caseNo, CaseStatus s) {
        status = s.name();
        act.setStatus(caseNo, s);
    }

    @Override
    public void onMaterialsSubmitted(String batchJson) {
        materialsSignal = true;
    }

    @Override
    public void onHumanDecision(HumanDecision decision) {
        decisions.add(decision);
    }

    @Override
    public void onCustomerDispute(String disputeJson) {
        disputed = true;
    }

    @Override
    public String state() {
        return status + "|" + channel;
    }
}