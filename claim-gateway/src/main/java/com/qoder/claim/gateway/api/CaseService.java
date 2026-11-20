package com.qoder.claim.gateway.api;

import com.qoder.claim.common.model.CaseStatus;
import com.qoder.claim.common.model.ClaimCase;
import com.qoder.claim.common.model.ClaimInput;
import com.qoder.claim.common.model.HumanDecision;
import com.qoder.claim.common.model.Material;
import com.qoder.claim.common.port.CaseRepository;
import com.qoder.claim.common.port.MaterialFileStore;
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
    private final ObjectProvider<MaterialFileStore> fileStore;
    private final JdbcTemplate jdbc;

    public CaseService(JdbcCaseRepository cases, MaterialRepository materials, ApprovalService approval,
                       ObjectProvider<WorkflowStarter> temporal, ObjectProvider<LocalPipelineRunner> local,
                       ObjectProvider<MaterialFileStore> fileStore, JdbcTemplate jdbc) {
        this.cases = cases;
        this.materials = materials;
        this.approval = approval;
        this.temporal = temporal;
        this.local = local;
        this.fileStore = fileStore;
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
        List<Material> existing = materials.list(caseNo);
        for (Material m : batch) {
            m.setCaseNo(caseNo);
            if (m.getMaterialId() == null) {
                m.setMaterialId(caseNo + "-M-" + UUID.randomUUID().toString().substring(0, 6));
            }
            if (m.getSha256() == null) {
                m.setSha256(sha256(m.getStorageKey() == null ? m.getMaterialId() : m.getStorageKey()));
            }
            // 幂等去重：同案同类型同哈希视为同一材料，重复登记直接跳过
            boolean dup = existing.stream().anyMatch(x -> x.getType() != null && x.getType().equals(m.getType())
                    && m.getSha256().equals(x.getSha256()));
            if (dup) {
                continue;
            }
            materials.save(m);
            existing.add(m);
        }
        signalMaterials(caseNo);
    }

    private static final java.util.Set<String> FILE_EXTS = java.util.Set.of("jpg", "jpeg", "png", "gif", "webp", "pdf");
    private static final Map<String, String> EXT_CONTENT_TYPE = Map.of(
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png",
            "gif", "image/gif", "webp", "image/webp", "pdf", "application/pdf");

    /** 真实文件上传：字节进对象存储，SHA-256 算文件内容（登记即证据，读取时复核）。 */
    public Material uploadMaterial(String caseNo, String type, double confidence,
                                   String originalName, byte[] bytes) {
        require(caseNo);
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("材料类型必填");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件为空");
        }
        String name = sanitizeFileName(originalName);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!FILE_EXTS.contains(ext)) {
            throw new IllegalArgumentException("仅支持 jpg/jpeg/png/gif/webp/pdf 证据文件: " + name);
        }
        // MIME 一律由白名单扩展名推导，不信任客户端声明，防止内容类型伪装
        String ct = EXT_CONTENT_TYPE.get(ext);
        String contentHash = sha256(bytes);
        // 幂等去重：同案同类型同内容哈希的重复上传返回原材料，不重复落对象
        Material dup = materials.list(caseNo).stream()
                .filter(x -> type.equals(x.getType()) && contentHash.equals(x.getSha256()))
                .findFirst().orElse(null);
        if (dup != null) {
            return dup;
        }
        String materialId = caseNo + "-M-" + UUID.randomUUID().toString().substring(0, 6);
        String objectKey = "materials/" + caseNo + "/" + materialId;
        fileStore.getObject().put(objectKey, bytes, ct);
        Material m = new Material();
        m.setMaterialId(materialId);
        m.setCaseNo(caseNo);
        m.setType(type);
        m.setStorageKey(objectKey);
        m.setSha256(contentHash);
        m.setConfidence(confidence);
        m.setFileName(name);
        m.setContentType(ct);
        m.setSizeBytes((long) bytes.length);
        materials.save(m);
        signalMaterials(caseNo);
        return m;
    }

    public record MaterialFile(Material material, byte[] bytes) {}

    /** 读取材料文件本体；仅登记材料无本体，读回字节复核 SHA-256 防篡改。 */
    public MaterialFile readMaterial(String caseNo, String materialId) {
        require(caseNo);
        Material m = materials.list(caseNo).stream()
                .filter(x -> x.getMaterialId().equals(materialId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("材料不存在: " + materialId));
        if (m.getSizeBytes() == null) {
            throw new IllegalArgumentException("仅登记材料，无文件本体: " + materialId);
        }
        byte[] bytes = fileStore.getObject().get(m.getStorageKey());
        if (!sha256(bytes).equals(m.getSha256())) {
            throw new IllegalStateException("材料文件哈希与登记不一致（疑似篡改）: " + materialId);
        }
        return new MaterialFile(m, bytes);
    }

    private void signalMaterials(String caseNo) {
        WorkflowStarter t = temporal.getIfAvailable();
        if (t != null) {
            t.signalMaterials(caseNo);
        }
    }

    private static String sanitizeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "file";
        }
        String name = originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    public void humanDecision(String caseNo, HumanDecision d) {
        require(caseNo);
        // 服务端兜底：人工批准必须带正金额，防止「批准但金额0」被规则关闭成误拒赔
        if ("MANUAL_HANDLE".equals(d.getStep()) && d.isApproved() && d.getAmountCents() <= 0) {
            throw new InvalidDecisionException("人工处理批准必须填写大于0的赔付金额；不赔付请选择驳回/关闭");
        }
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
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
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