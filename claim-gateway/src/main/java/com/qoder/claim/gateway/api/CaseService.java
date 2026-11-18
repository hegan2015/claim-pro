package com.qoder.claim.gateway.api;

import com.qoder.claim.common.model.CaseStatus;
import com.qoder.claim.common.model.ClaimCase;
import com.qoder.claim.common.model.ClaimInput;
import com.qoder.claim.common.model.HumanDecision;
import com.qoder.claim.common.model.Material;
import com.qoder.claim.common.port.CaseRepository;
import com.qoder.claim.common.port.MaterialRepository;
import com.qoder.claim.core.approval.ApprovalService;
import com.qoder.claim.infra.jdbc.JdbcCaseRepository;
import com.qoder.claim.workflow.LocalPipelineRunner;
import com.qoder.claim.workflow.WorkflowStarter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API 编排门面：报案→工作流（Temporal 或本地开发管道），人工动作→审批记录+signal。 */
@Service
public class CaseService {

    private final JdbcCaseRepository cases;
    private final MaterialRepository materials;
    private final ApprovalService approval;
    private final ObjectProvider<WorkflowStarter> temporal;
    private final ObjectProvider<LocalPipelineRunner> local;
    private final JdbcTemplate jdbc;

    public CaseService(JdbcCaseRepository cases, MaterialRepository materials, ApprovalService approval,
                       ObjectProvider<WorkflowStarter> temporal, ObjectProvider<LocalPipelineRunner> local,
                       JdbcTemplate jdbc) {
        this.cases = cases;
        this.materials = materials;
        this.approval = approval;
        this.temporal = temporal;
        this.local = local;
        this.jdbc = jdbc;
    }

    public ClaimCase report(ClaimInput input) {
        String caseNo = "CLM-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        input.setCaseNo(caseNo);
        if (input.getAccidentDate() == null) {
            input.setAccidentDate(LocalDate.now());
        }
        if (input.getOrg() == null) {
            input.setOrg("HEAD");
        }
        if (input.getInsuranceType() == null) {
            input.setInsuranceType("AUTO");
        }
        WorkflowStarter t = temporal.getIfAvailable();
        if (t != null) {
            t.start(input);
        } else {
            LocalPipelineRunner l = local.getIfAvailable();
            if (l == null) {
                throw new IllegalStateException("无可用编排引擎：请启动 Temporal 或启用本地开发管道");
            }
            l.start(input);
        }
        return cases.find(caseNo).orElseThrow();
    }

    public void submitMaterials(String caseNo, List<Material> batch) {
        require(caseNo);
        for (Material m : batch) {
            m.setCaseNo(caseNo);
            if (m.getMaterialId() == null) {
                m.setMaterialId(caseNo + "-M-" + UUID.randomUUID().toString().substring(0, 6));
            }
            if (m.getSha256() == null) {
                m.setSha256(sha256(m.getStorageKey() == null ? m.getMaterialId() : m.getStorageKey()));
            }
            materials.save(m);
        }
        WorkflowStarter t = temporal.getIfAvailable();
        if (t != null) {
            t.signalMaterials(caseNo);
        }
    }

    public void humanDecision(String caseNo, HumanDecision d) {
        require(caseNo);
        d.setCaseNo(caseNo);
        approval.record(caseNo, d.getStep(), d.getOperator(), d.isApproved(), d.getComment(), d.getAmountCents());
        WorkflowStarter t = temporal.getIfAvailable();
        if (t != null) {
            t.signalDecision(caseNo, d);
        }
    }

    public void dispute(String caseNo, String reason) {
        require(caseNo);
        WorkflowStarter t = temporal.getIfAvailable();
        if (t != null) {
            t.signalDispute(caseNo, "{\"reason\":\"" + reason + "\"}");
        } else {
            cases.updateStatus(caseNo, CaseStatus.MANUAL);
        }
    }

    public Map<String, Object> detail(String caseNo) {
        ClaimCase c = require(caseNo);
        return Map.of(
                "case", c,
                "materials", materials.list(caseNo),
                "approvals", approval.list(caseNo),
                "events", jdbc.queryForList("SELECT * FROM claim_decision WHERE case_no=? ORDER BY ts, id", caseNo),
                "ledgerBusiness", jdbc.queryForList("SELECT * FROM ledger_business WHERE case_no=? ORDER BY ts", caseNo),
                "ledgerFund", jdbc.queryForList("SELECT * FROM ledger_fund WHERE case_no=? ORDER BY ts", caseNo));
    }

    private ClaimCase require(String caseNo) {
        return cases.find(caseNo).orElseThrow(() -> new IllegalArgumentException("案件不存在: " + caseNo));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}