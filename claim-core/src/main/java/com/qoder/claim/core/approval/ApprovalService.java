package com.qoder.claim.core.approval;

import com.qoder.claim.common.model.Channel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 分级审批（确定性，LLM 禁入）。
 * 快赔通道自动放行；标准通道单人审批；>10万元双人审批。
 * 拒赔的人工确认在 workflow 层强制，不经本服务自动通过。
 */
@Service
public class ApprovalService {

    private final JdbcTemplate jdbc;

    public ApprovalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int requiredApprovers(long amountCents, Channel channel) {
        if (channel == Channel.FAST) {
            return 0;
        }
        if (amountCents > 100_000_00L) {
            return 2;
        }
        return 1;
    }

    public void record(String caseNo, String step, String operator, boolean approved, String comment) {
        record(caseNo, step, operator, approved, comment, 0);
    }

    public void record(String caseNo, String step, String operator, boolean approved, String comment, long amountCents) {
        jdbc.update("INSERT INTO claim_approval(case_no, step, operator, approved, comment, amount_cents, ts) VALUES(?,?,?,?,?,?,?)",
                caseNo, step, operator, approved ? 1 : 0, comment, amountCents, System.currentTimeMillis());
    }

    public Map<String, Object> latestDecision(String caseNo, String step) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM claim_approval WHERE case_no=? AND step=? ORDER BY ts DESC, id DESC LIMIT 1",
                caseNo, step);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long approvalCount(String caseNo, String step) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claim_approval WHERE case_no=? AND step=? AND approved=1",
                Long.class, caseNo, step);
        return n == null ? 0 : n;
    }

    public boolean hasRejection(String caseNo, String step) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claim_approval WHERE case_no=? AND step=? AND approved=0",
                Long.class, caseNo, step);
        return n != null && n > 0;
    }

    public List<Map<String, Object>> list(String caseNo) {
        return jdbc.queryForList("SELECT * FROM claim_approval WHERE case_no=? ORDER BY ts", caseNo);
    }
}