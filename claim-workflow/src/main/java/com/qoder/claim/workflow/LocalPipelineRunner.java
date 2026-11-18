package com.qoder.claim.workflow;

import com.qoder.claim.common.model.AssessResult;
import com.qoder.claim.common.model.CalcResult;
import com.qoder.claim.common.model.CaseStatus;
import com.qoder.claim.common.model.Channel;
import com.qoder.claim.common.model.ClaimCase;
import com.qoder.claim.common.model.ClaimInput;
import com.qoder.claim.common.port.CaseRepository;
import com.qoder.claim.core.approval.ApprovalService;
import com.qoder.claim.core.rule.CoverageDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地开发管道（claim.temporal.enabled=false 时启用，启动即告警）。
 * 与 ClaimWorkflowImpl 同构的状态机，人工节点以轮询 claim_approval 表代替 Temporal signal。
 * 仅供无 Temporal 环境的功能验证，生产编排一律走 Temporal（durable 执行天然跨重启恢复）。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "claim.temporal", name = "enabled", havingValue = "false")
public class LocalPipelineRunner {

    private final ClaimActivities act;
    private final ApprovalService approval;
    private final CaseRepository cases;
    private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> new Thread(r, "local-pipeline"));

    public LocalPipelineRunner(ClaimActivities act, ApprovalService approval, CaseRepository cases, JdbcTemplate jdbc) {
        this.act = act;
        this.approval = approval;
        this.cases = cases;
        this.jdbc = jdbc;
        log.warn("Temporal 已禁用（claim.temporal.enabled=false）：本地开发管道运行中，非生产编排");
    }

    /** 本地模式重启恢复：内存管道线程不持久，按当前状态续接等待节点（生产由 Temporal durable 保证）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int n = 0;
        for (ClaimCase c : cases.list(null, null, 500)) {
            long deadline = System.currentTimeMillis() + 30 * 60_000;
            switch (c.getStatus()) {
                case REPORTED, MATERIALS_PENDING -> {
                    pool.submit(() -> run(inputFrom(c)));
                    n++;
                }
                case APPROVING -> {
                    pool.submit(() -> resumeApproval(c.getCaseNo(), deadline));
                    n++;
                }
                case MANUAL -> {
                    pool.submit(() -> resumeManual(c.getCaseNo(), deadline));
                    n++;
                }
                case CLOSED, SETTLED, REJECTED -> { /* 终结态无需恢复 */ }
                default -> log.warn("案件 {} 处于中间态 {}，本地模式不自动续接（生产 Temporal 无此限制）",
                        c.getCaseNo(), c.getStatus());
            }
        }
        if (n > 0) {
            log.warn("本地管道已恢复 {} 个未决案件的等待节点", n);
        }
    }

    private static ClaimInput inputFrom(ClaimCase c) {
        ClaimInput in = new ClaimInput();
        in.setCaseNo(c.getCaseNo());
        in.setPolicyNo(c.getPolicyNo());
        in.setOrg(c.getOrg());
        in.setInsuranceType(c.getInsuranceType());
        in.setAccidentDate(c.getAccidentDate());
        in.setAccidentType(c.getAccidentType());
        in.setDescription(c.getDescription());
        return in;
    }

    public void start(ClaimInput input) {
        act.createCase(input); // 同步建案：API 返回前案件行必须已存在
        pool.submit(() -> run(input));
    }

    private void run(ClaimInput input) {
        String caseNo = input.getCaseNo();
        try {
            act.createCase(input);
            long deadline = System.currentTimeMillis() + 30 * 60_000;
            while (!act.materialsComplete(caseNo)) {
                act.setStatus(caseNo, CaseStatus.MATERIALS_PENDING);
                if (!sleepUntil(deadline)) {
                    return;
                }
            }
            act.setStatus(caseNo, CaseStatus.COVERAGE_CHECK);
            CoverageDecision cov = act.coverageCheck(caseNo);
            if (!cov.isCovered()) {
                act.setStatus(caseNo, CaseStatus.APPROVING);
                Map<String, Object> d = awaitDecision(caseNo, "REJECT_CONFIRM", deadline);
                if (d != null && ((Number) d.get("approved")).intValue() == 1) {
                    act.reject(caseNo, String.join(",", cov.getExclusions()));
                    act.notify(caseNo, "REJECTED");
                } else {
                    act.setStatus(caseNo, CaseStatus.MANUAL);
                }
                return;
            }
            act.setStatus(caseNo, CaseStatus.ASSESSING);
            int risk;
            AssessResult assess;
            try {
                risk = act.fraudScore(caseNo);
                assess = act.assess(caseNo);
            } catch (Exception e) {
                toManual(caseNo, "模型/识别服务不可用，降级人工通道: " + e.getMessage(), deadline);
                return;
            }
            Channel ch = act.route(caseNo, risk, assess.getEstimateCents());
            act.setStatus(caseNo, CaseStatus.ROUTED);
            if (ch == Channel.MANUAL || ch == Channel.INVESTIGATE) {
                toManual(caseNo, ch == Channel.INVESTIGATE ? "高风险/大额，SIU 人工调查" : "人工通道", deadline);
                return;
            }
            CalcResult calc = act.calculate(caseNo);
            act.setStatus(caseNo, CaseStatus.CALCULATED);
            int need = act.requiredApprovers(caseNo);
            if (need > 0) {
                act.setStatus(caseNo, CaseStatus.APPROVING);
                long approvedCount = 0;
                while (approvedCount < need) {
                    Map<String, Object> d = awaitDecision(caseNo, "APPROVE", deadline);
                    if (d == null) {
                        return;
                    }
                    if (((Number) d.get("approved")).intValue() == 0) {
                        act.reject(caseNo, "审批驳回: " + d.get("comment"));
                        act.notify(caseNo, "REJECTED");
                        return;
                    }
                    approvedCount = approval.approvalCount(caseNo, "APPROVE");
                }
            }
            payAndClose(caseNo, calc.getPayableCents());
        } catch (Exception e) {
            log.error("本地管道异常 case={}", caseNo, e);
            act.setStatus(caseNo, CaseStatus.MANUAL);
        }
    }

    private void toManual(String caseNo, String reason, long deadline) {
        act.setStatus(caseNo, CaseStatus.MANUAL);
        act.archive(caseNo, "MANUAL", reason);
        act.notify(caseNo, "MANUAL");
        resumeManual(caseNo, deadline);
    }

    /** MANUAL 等待节点续接（新降级与重启恢复共用）。 */
    private void resumeManual(String caseNo, long deadline) {
        Map<String, Object> d = awaitDecision(caseNo, "MANUAL_HANDLE", deadline);
        if (d != null && ((Number) d.get("approved")).intValue() == 1) {
            long amount = ((Number) d.get("amount_cents")).longValue();
            if (amount > 0) {
                payAndClose(caseNo, amount);
                return;
            }
        }
        act.reject(caseNo, "人工处理关闭: " + (d == null ? "超时" : d.get("comment")));
    }

    /** APPROVING 等待节点续接：按最近核保结论区分 赔付审批 / 拒赔确认。 */
    private void resumeApproval(String caseNo, long deadline) {
        boolean covered = true;
        List<Map<String, Object>> cov = jdbc.queryForList(
                "SELECT output_json FROM claim_decision WHERE case_no=? AND step='COVERAGE' ORDER BY ts DESC, id DESC LIMIT 1",
                caseNo);
        if (!cov.isEmpty()) {
            Object out = cov.get(0).get("output_json");
            if (out == null) {
                out = cov.get(0).get("OUTPUT_JSON");
            }
            covered = out == null || !String.valueOf(out).contains("\"covered\":false");
        }
        if (!covered) {
            Map<String, Object> d = awaitDecision(caseNo, "REJECT_CONFIRM", deadline);
            if (d != null && ((Number) d.get("approved")).intValue() == 1) {
                act.reject(caseNo, "核保免责拒赔（恢复续接）");
                act.notify(caseNo, "REJECTED");
            } else {
                toManual(caseNo, "拒赔未确认，转人工", deadline);
            }
            return;
        }
        int need = act.requiredApprovers(caseNo);
        long approvedCount = approval.approvalCount(caseNo, "APPROVE");
        while (approvedCount < need) {
            Map<String, Object> d = awaitDecision(caseNo, "APPROVE", deadline);
            if (d == null) {
                return;
            }
            if (((Number) d.get("approved")).intValue() == 0) {
                act.reject(caseNo, "审批驳回: " + d.get("comment"));
                act.notify(caseNo, "REJECTED");
                return;
            }
            approvedCount = approval.approvalCount(caseNo, "APPROVE");
        }
        payAndClose(caseNo, cases.find(caseNo).map(ClaimCase::getPayableCents).orElse(0L));
    }

    private void payAndClose(String caseNo, long amountCents) {
        act.setStatus(caseNo, CaseStatus.PAYING);
        act.pay(caseNo, amountCents);
        act.registerCore(caseNo);
        act.setStatus(caseNo, CaseStatus.SETTLED);
        act.notify(caseNo, "SETTLED");
        act.setStatus(caseNo, CaseStatus.CLOSED);
    }

    private Map<String, Object> awaitDecision(String caseNo, String step, long deadline) {
        long seen = approval.approvalCount(caseNo, step) + (approval.hasRejection(caseNo, step) ? 1 : 0);
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> latest = approval.latestDecision(caseNo, step);
            long total = approval.approvalCount(caseNo, step) + (approval.hasRejection(caseNo, step) ? 1 : 0);
            if (latest != null && total > seen) {
                return latest;
            }
            sleep(2000);
        }
        return null;
    }

    private boolean sleepUntil(long deadline) {
        while (System.currentTimeMillis() < deadline) {
            sleep(2000);
            return true;
        }
        return false;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}