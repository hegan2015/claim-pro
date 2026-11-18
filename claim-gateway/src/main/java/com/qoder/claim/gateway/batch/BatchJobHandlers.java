package com.qoder.claim.gateway.batch;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * XXL-Job 任务体：委托与手动 API/启动跑相同的服务，触发方记 XXL_JOB（或任务参数）。
 * 金标出现漂移 → handleFail，调度中心记失败并可配告警/重试。
 */
@Component
public class BatchJobHandlers {

    private final GraphBuildService graph;
    private final GoldRegressionService gold;

    public BatchJobHandlers(GraphBuildService graph, GoldRegressionService gold) {
        this.graph = graph;
        this.gold = gold;
    }

    @XxlJob("graphBuildJobHandler")
    public void graphBuild() {
        try {
            Map<String, Object> r = graph.build(triggerBy());
            XxlJobHelper.log("图谱构建完成: nodes={} edges={} shops={} newFlags={} {}ms",
                    r.get("nodeCount"), r.get("edgeCount"), r.get("shopCount"),
                    r.get("newFlagCount"), r.get("durationMs"));
        } catch (Exception e) {
            XxlJobHelper.log(e);
            XxlJobHelper.handleFail("图谱构建失败: " + e.getMessage());
        }
    }

    @XxlJob("goldRegressionJobHandler")
    public void goldRegression() {
        try {
            Map<String, Object> r = gold.run(triggerBy());
            XxlJobHelper.log("金标回归完成: {}/{} 通过, {}ms", r.get("passed"), r.get("total"), r.get("durationMs"));
            Object failed = r.get("failed");
            if (failed instanceof Number n && n.intValue() > 0) {
                XxlJobHelper.handleFail("金标漂移 " + n.intValue() + " 案，详见 /api/gold/latest");
            }
        } catch (Exception e) {
            XxlJobHelper.log(e);
            XxlJobHelper.handleFail("金标回归失败: " + e.getMessage());
        }
    }

    private static String triggerBy() {
        String p = XxlJobHelper.getJobParam();
        if (p == null || p.isBlank()) {
            return "XXL_JOB";
        }
        return p.length() > 16 ? p.substring(0, 16) : p;
    }
}