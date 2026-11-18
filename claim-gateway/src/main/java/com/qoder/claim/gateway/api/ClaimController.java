package com.qoder.claim.gateway.api;

import com.qoder.claim.common.model.ClaimCase;
import com.qoder.claim.common.model.ClaimInput;
import com.qoder.claim.common.model.HumanDecision;
import com.qoder.claim.common.model.Material;
import com.qoder.claim.common.port.EvidenceStore;
import com.qoder.claim.core.ledger.ReconciliationService;
import com.qoder.claim.infra.jdbc.JdbcCaseRepository;
import com.qoder.claim.workflow.LocalPipelineRunner;
import com.qoder.claim.workflow.WorkflowStarter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ClaimController {

    private final CaseService service;
    private final JdbcCaseRepository cases;
    private final ReconciliationService recon;
    private final ObjectProvider<EvidenceStore> evidence;
    private final ObjectProvider<WorkflowStarter> temporal;
    private final ObjectProvider<LocalPipelineRunner> localPipeline;
    private final JdbcTemplate jdbc;

    public ClaimController(CaseService service, JdbcCaseRepository cases, ReconciliationService recon,
                           ObjectProvider<EvidenceStore> evidence, ObjectProvider<WorkflowStarter> temporal,
                           ObjectProvider<LocalPipelineRunner> localPipeline, JdbcTemplate jdbc) {
        this.service = service;
        this.cases = cases;
        this.recon = recon;
        this.evidence = evidence;
        this.temporal = temporal;
        this.localPipeline = localPipeline;
        this.jdbc = jdbc;
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        boolean t = temporal.getIfAvailable() != null;
        return Map.of(
                "orchestration", t ? "TEMPORAL" : "LOCAL",
                "evidenceStore", evidence.getIfAvailable() != null ? evidence.getIfAvailable().getClass().getSimpleName() : "NONE");
    }

    @PostMapping("/claims")
    public ClaimCase report(@RequestBody ClaimInput input) {
        return service.report(input);
    }

    @GetMapping("/claims")
    public List<ClaimCase> list(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String channel,
                                @RequestParam(defaultValue = "100") int limit) {
        return cases.list(status, channel, limit);
    }

    @GetMapping("/claims/{caseNo}")
    public Map<String, Object> detail(@PathVariable String caseNo) {
        return service.detail(caseNo);
    }

    @PostMapping("/claims/{caseNo}/materials")
    public Map<String, Object> materials(@PathVariable String caseNo, @RequestBody List<Material> batch) {
        service.submitMaterials(caseNo, batch);
        return Map.of("ok", true, "count", batch.size());
    }

    @PostMapping("/claims/{caseNo}/materials/upload")
    public Material uploadMaterial(@PathVariable String caseNo,
                                   @RequestParam("file") MultipartFile file,
                                   @RequestParam("type") String type,
                                   @RequestParam(value = "confidence", defaultValue = "0") double confidence) throws IOException {
        return service.uploadMaterial(caseNo, type, confidence, file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/claims/{caseNo}/materials/{materialId}/content")
    public ResponseEntity<byte[]> materialContent(@PathVariable String caseNo, @PathVariable String materialId) {
        CaseService.MaterialFile f = service.readMaterial(caseNo, materialId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(f.material().getContentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        String filename = f.material().getFileName() == null ? materialId : f.material().getFileName();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(f.bytes());
    }

    @PostMapping("/claims/{caseNo}/decision")
    public Map<String, Object> decision(@PathVariable String caseNo, @RequestBody HumanDecision d) {
        service.humanDecision(caseNo, d);
        return Map.of("ok", true);
    }

    @PostMapping("/claims/{caseNo}/dispute")
    public Map<String, Object> dispute(@PathVariable String caseNo, @RequestBody Map<String, String> body) {
        service.dispute(caseNo, body.getOrDefault("reason", ""));
        return Map.of("ok", true);
    }

    @GetMapping("/claims/{caseNo}/evidence/verify")
    public Map<String, Object> verifyEvidence(@PathVariable String caseNo) {
        EvidenceStore store = evidence.getIfAvailable();
        boolean ok = store != null && store.verifyChain(caseNo);
        return Map.of("caseNo", caseNo, "chainValid", ok);
    }

    @GetMapping("/claims/{caseNo}/tool-audit")
    public List<Map<String, Object>> toolAudit(@PathVariable String caseNo) {
        return jdbc.queryForList("SELECT * FROM tool_audit WHERE case_no=? ORDER BY ts, id", caseNo);
    }

    @GetMapping("/recon/diffs")
    public List<Map<String, Object>> reconDiffs() {
        return recon.openDiffs();
    }

    @PostMapping("/recon/run")
    public Map<String, Object> runRecon() {
        return Map.of("newDiffs", recon.reconcile());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> byStatus = new java.util.LinkedHashMap<>();
        jdbc.queryForList("SELECT status, COUNT(*) n FROM claim_case GROUP BY status")
                .forEach(r -> byStatus.put((String) r.get("status"), r.get("n")));
        Map<String, Object> byChannel = new java.util.LinkedHashMap<>();
        jdbc.queryForList("SELECT channel, COUNT(*) n FROM claim_case WHERE channel IS NOT NULL GROUP BY channel")
                .forEach(r -> byChannel.put((String) r.get("channel"), r.get("n")));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM claim_case", Long.class);
        Long fast = jdbc.queryForObject("SELECT COUNT(*) FROM claim_case WHERE channel='FAST'", Long.class);
        Long paid = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents),0) FROM ledger_fund WHERE direction='OUT'", Long.class);
        Long promptTokens = jdbc.queryForObject("SELECT COALESCE(SUM(prompt_tokens),0) FROM claim_decision", Long.class);
        Long completionTokens = jdbc.queryForObject("SELECT COALESCE(SUM(completion_tokens),0) FROM claim_decision", Long.class);
        Long openDiffs = jdbc.queryForObject("SELECT COUNT(*) FROM recon_diff WHERE status='OPEN'", Long.class);
        return Map.of(
                "totalCases", total == null ? 0 : total,
                "fastCases", fast == null ? 0 : fast,
                "byStatus", byStatus,
                "byChannel", byChannel,
                "paidCents", paid == null ? 0 : paid,
                "promptTokens", promptTokens == null ? 0 : promptTokens,
                "completionTokens", completionTokens == null ? 0 : completionTokens,
                "openReconDiffs", openDiffs == null ? 0 : openDiffs);
    }

    @GetMapping("/audit/recent")
    public List<Map<String, Object>> recentAudit(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("SELECT * FROM claim_decision ORDER BY ts DESC, id DESC LIMIT " + Math.min(limit, 200));
    }
}