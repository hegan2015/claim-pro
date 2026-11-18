package com.qoder.claim.infra.jdbc;

import com.qoder.claim.common.model.CaseStatus;
import com.qoder.claim.common.model.Channel;
import com.qoder.claim.common.model.ClaimCase;
import com.qoder.claim.common.port.CaseRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCaseRepository implements CaseRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<ClaimCase> MAPPER = (rs, i) -> {
        ClaimCase c = new ClaimCase();
        c.setCaseNo(rs.getString("case_no"));
        c.setPolicyNo(rs.getString("policy_no"));
        c.setOrg(rs.getString("org"));
        c.setInsuranceType(rs.getString("insurance_type"));
        Date d = rs.getDate("accident_date");
        c.setAccidentDate(d == null ? null : d.toLocalDate());
        c.setAccidentType(rs.getString("accident_type"));
        c.setStatus(CaseStatus.valueOf(rs.getString("status")));
        String ch = rs.getString("channel");
        c.setChannel(ch == null || ch.isEmpty() ? null : Channel.valueOf(ch));
        c.setEstimateCents(rs.getLong("estimate_cents"));
        c.setPayableCents(rs.getLong("payable_cents"));
        c.setRiskScore(rs.getInt("risk_score"));
        c.setRecognitionConfidence(rs.getDouble("recognition_confidence"));
        c.setRejectReason(rs.getString("reject_reason"));
        c.setDescription(rs.getString("description"));
        c.setCreatedAt(rs.getLong("created_at"));
        c.setUpdatedAt(rs.getLong("updated_at"));
        return c;
    };

    public JdbcCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ClaimCase c) {
        jdbc.update("INSERT INTO claim_case(case_no, policy_no, org, insurance_type, accident_date, accident_type, " +
                        "status, channel, estimate_cents, payable_cents, risk_score, recognition_confidence, " +
                        "reject_reason, description, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                c.getCaseNo(), c.getPolicyNo(), c.getOrg(), c.getInsuranceType(),
                c.getAccidentDate() == null ? null : Date.valueOf(c.getAccidentDate()),
                c.getAccidentType(), c.getStatus().name(), c.getChannel() == null ? null : c.getChannel().name(),
                c.getEstimateCents(), c.getPayableCents(), c.getRiskScore(), c.getRecognitionConfidence(),
                c.getRejectReason(), c.getDescription(), c.getCreatedAt(), c.getUpdatedAt());
    }

    @Override
    public void update(ClaimCase c) {
        jdbc.update("UPDATE claim_case SET status=?, channel=?, estimate_cents=?, payable_cents=?, risk_score=?, " +
                        "recognition_confidence=?, reject_reason=?, updated_at=? WHERE case_no=?",
                c.getStatus().name(), c.getChannel() == null ? null : c.getChannel().name(),
                c.getEstimateCents(), c.getPayableCents(), c.getRiskScore(), c.getRecognitionConfidence(),
                c.getRejectReason(), System.currentTimeMillis(), c.getCaseNo());
    }

    @Override
    public Optional<ClaimCase> find(String caseNo) {
        List<ClaimCase> list = jdbc.query("SELECT * FROM claim_case WHERE case_no=?", MAPPER, caseNo);
        return list.stream().findFirst();
    }

    @Override
    public void updateStatus(String caseNo, CaseStatus status) {
        jdbc.update("UPDATE claim_case SET status=?, updated_at=? WHERE case_no=?",
                status.name(), System.currentTimeMillis(), caseNo);
    }

    public List<ClaimCase> list(String status, String channel, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM claim_case WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isEmpty()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (channel != null && !channel.isEmpty()) {
            sql.append(" AND channel=?");
            args.add(channel);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ").append(Math.max(1, Math.min(limit, 500)));
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}