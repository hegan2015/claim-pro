package com.qoder.claim.gateway.api;

import com.qoder.claim.agents.AgentFacade;
import com.qoder.claim.agents.AgentUnavailableException;
import com.qoder.claim.common.port.CaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentFacade agents;
    private final CaseRepository cases;
    private final ClauseService clauses;

    public AgentController(AgentFacade agents, CaseRepository cases, ClauseService clauses) {
        this.agents = agents;
        this.cases = cases;
        this.clauses = clauses;
    }

    @GetMapping("/availability")
    public Map<String, Object> availability() {
        return Map.of(
                "intake", agents.available("intake"),
                "assess", agents.available("assess"),
                "coverage-explain", agents.available("coverage-explain"),
                "fraud-report", agents.available("fraud-report"),
                "review", agents.available("review"),
                "notify", agents.available("notify"),
                "calc-draft", agents.available("calc-draft"),
                "doc-vision", agents.available("doc-vision"));
    }

    @PostMapping("/coverage-explain/{caseNo}")
    public ResponseEntity<?> coverageExplain(@PathVariable String caseNo) {
        var c = cases.find(caseNo).orElse(null);
        if (c == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "案件不存在"));
        }
        try {
            return ResponseEntity.ok(clauses.explain(caseNo, c.getDescription()));
        } catch (AgentUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/intake")
    public ResponseEntity<?> intake(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(Map.of("structured", agents.intakeStructure(body.getOrDefault("description", ""))));
        } catch (AgentUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/notify")
    public ResponseEntity<?> notify(@RequestBody Map<String, String> body) {
        String caseNo = body.get("caseNo");
        var c = cases.find(caseNo).orElse(null);
        if (c == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "案件不存在"));
        }
        try {
            String input = "{\"caseStatus\":\"" + c.getStatus() + "\",\"channel\":\"" + c.getChannel()
                    + "\",\"payableCents\":" + c.getPayableCents() + ",\"question\":\""
                    + body.getOrDefault("question", "") + "\"}";
            return ResponseEntity.ok(Map.of("reply", agents.notifyReply(input)));
        } catch (AgentUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/review/{caseNo}")
    public ResponseEntity<?> review(@PathVariable String caseNo) {
        var c = cases.find(caseNo).orElse(null);
        if (c == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "案件不存在"));
        }
        try {
            String dossier = "{\"caseNo\":\"" + caseNo + "\",\"estimateCents\":" + c.getEstimateCents()
                    + ",\"payableCents\":" + c.getPayableCents() + ",\"riskScore\":" + c.getRiskScore() + "}";
            return ResponseEntity.ok(Map.of("review", agents.review(caseNo, dossier)));
        } catch (AgentUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        }
    }
}