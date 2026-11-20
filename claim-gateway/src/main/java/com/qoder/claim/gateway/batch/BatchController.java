package com.qoder.claim.gateway.batch;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 风控批处理 API：图谱构建 / 金标回归，手动触发 + 结果查询（定时任务见 BatchScheduler）。 */
@RestController
@RequestMapping("/api")
public class BatchController {

    private final GraphBuildService graph;
    private final GoldRegressionService gold;
    private final org.springframework.beans.factory.ObjectProvider<com.qoder.claim.infra.graph.NebulaGraphStore> nebula;
    private final com.qoder.claim.core.fraud.FraudScoringStrategy fraud;
    private final ShadowReplayService shadow;
    private final com.qoder.claim.workflow.CanaryService canary;

    public BatchController(GraphBuildService graph, GoldRegressionService gold,
                           org.springframework.beans.factory.ObjectProvider<com.qoder.claim.infra.graph.NebulaGraphStore> nebula,
                           com.qoder.claim.core.fraud.FraudScoringStrategy fraud,
                           ShadowReplayService shadow, com.qoder.claim.workflow.CanaryService canary) {
        this.graph = graph;
        this.gold = gold;
        this.nebula = nebula;
        this.fraud = fraud;
        this.shadow = shadow;
        this.canary = canary;
    }

    @PostMapping("/shadow/run")
    public Map<String, Object> shadowRun(@org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> candidate) {
        return shadow.run(candidate == null ? Map.of() : candidate);
    }

    @GetMapping("/shadow/latest")
    public Map<String, Object> shadowLatest() {
        return shadow.latest();
    }

    @GetMapping("/canary/config")
    public Map<String, Object> canaryConfig() {
        return canary.config();
    }

    @PostMapping("/canary/config")
    public Map<String, Object> canaryUpdate(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        canary.update(body);
        return canary.config();
    }

    @GetMapping("/graph/nebula/status")
    public Map<String, Object> nebulaStatus() {
        com.qoder.claim.infra.graph.NebulaGraphStore store = nebula.getIfAvailable();
        return store == null ? Map.of("enabled", false) : store.status();
    }

    @GetMapping("/fraud/strategy")
    public Map<String, Object> fraudStrategy() {
        return Map.of("strategy", fraud.name());
    }

    @PostMapping("/graph/build")
    public Map<String, Object> buildGraph() {
        return graph.build("MANUAL");
    }

    @GetMapping("/graph/summary")
    public Map<String, Object> graphSummary() {
        return graph.summary();
    }

    @GetMapping("/graph/case/{caseNo}")
    public Map<String, Object> graphCase(@PathVariable String caseNo) {
        return graph.neighbourhood(caseNo);
    }

    @GetMapping("/graph/history")
    public List<Map<String, Object>> graphHistory() {
        return graph.history();
    }

    @PostMapping("/gold/run")
    public Map<String, Object> runGold() {
        return gold.run("MANUAL");
    }

    @GetMapping("/gold/latest")
    public Map<String, Object> goldLatest() {
        return gold.latest();
    }

    @GetMapping("/gold/history")
    public List<Map<String, Object>> goldHistory() {
        return gold.history();
    }
}