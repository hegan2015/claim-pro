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

    public BatchController(GraphBuildService graph, GoldRegressionService gold) {
        this.graph = graph;
        this.gold = gold;
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