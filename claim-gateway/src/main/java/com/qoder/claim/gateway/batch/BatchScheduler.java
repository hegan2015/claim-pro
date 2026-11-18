package com.qoder.claim.gateway.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 风控批处理调度：启动各跑一次（演示/重启即有数据）。
 * 日终 cron：XXL-Job 启用时（mysql/default）由调度中心接管（BatchJobHandlers），
 * 此处 Spring cron 仅在未启用 XXL-Job 的 local 零依赖档位兜底，避免双调度。
 */
@Slf4j
@Configuration
@EnableScheduling
public class BatchScheduler {

    private final GraphBuildService graph;
    private final GoldRegressionService gold;

    public BatchScheduler(GraphBuildService graph, GoldRegressionService gold) {
        this.graph = graph;
        this.gold = gold;
    }

    @Bean
    ApplicationRunner batchStartupRun() {
        return args -> {
            runQuietly("图谱构建[STARTUP]", () -> graph.build("STARTUP"));
            runQuietly("金标回归[STARTUP]", () -> gold.run("STARTUP"));
        };
    }

    // 批处理失败不阻断理赔主链路，只告警
    private void runQuietly(String label, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("{}失败: {}", label, e.getMessage(), e);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "claim.xxljob", name = "enabled", havingValue = "false")
    static class SpringCronFallback {

        private final GraphBuildService graph;
        private final GoldRegressionService gold;

        SpringCronFallback(GraphBuildService graph, GoldRegressionService gold) {
            this.graph = graph;
            this.gold = gold;
        }

        @Scheduled(cron = "${claim.batch.graph-cron:0 30 2 * * *}")
        public void graphDaily() {
            try {
                graph.build("SCHEDULED");
            } catch (Exception e) {
                log.error("图谱构建[SCHEDULED]失败: {}", e.getMessage(), e);
            }
        }

        @Scheduled(cron = "${claim.batch.gold-cron:0 0 3 * * *}")
        public void goldDaily() {
            try {
                gold.run("SCHEDULED");
            } catch (Exception e) {
                log.error("金标回归[SCHEDULED]失败: {}", e.getMessage(), e);
            }
        }
    }
}