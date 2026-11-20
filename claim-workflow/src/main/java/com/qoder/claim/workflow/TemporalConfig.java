package com.qoder.claim.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@EnableConfigurationProperties({TemporalProperties.class, RouteProperties.class, AssessGateProperties.class, CanaryProperties.class})
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "claim.temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowServiceStubs workflowServiceStubs(TemporalProperties props) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(props.getTarget()).build());
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs, TemporalProperties props) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(props.getNamespace()).build());
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Worker claimWorker(WorkerFactory factory, TemporalProperties props, ClaimActivitiesImpl activities) {
        // 六队列物理隔离（DESIGN 4.1）：每队列独立 worker + 独立并发限流；
        // activity 实现同一份，路由由 workflow 侧 stub 的 taskQueue 决定。
        Worker core = factory.newWorker(props.getTaskQueue(),
                io.temporal.worker.WorkerOptions.newBuilder()
                        .setMaxConcurrentActivityExecutionSize(props.getCoreConcurrency()).build());
        core.registerWorkflowImplementationTypes(ClaimWorkflowImpl.class);
        core.registerActivitiesImplementations(activities);
        int[] concurrency = {props.getIntakeConcurrency(), props.getDocConcurrency(), props.getAssessConcurrency(),
                props.getReviewConcurrency(), props.getFraudConcurrency()};
        String[] queues = {ClaimQueues.INTAKE, ClaimQueues.DOC, ClaimQueues.ASSESS, ClaimQueues.REVIEW, ClaimQueues.FRAUD};
        for (int i = 0; i < queues.length; i++) {
            Worker w = factory.newWorker(queues[i], io.temporal.worker.WorkerOptions.newBuilder()
                    .setMaxConcurrentActivityExecutionSize(concurrency[i]).build());
            w.registerActivitiesImplementations(activities);
        }
        return core;
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SmartLifecycle workerLifecycle(WorkerFactory factory, Worker worker, TemporalRuntime runtime) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                try {
                    factory.start();
                    runtime.setReady(true);
                    log.info("Temporal worker 已启动 queue={}", worker.getTaskQueue());
                } catch (Exception e) {
                    runtime.setReady(false);
                    log.error("Temporal 连接失败（{}）：工作流引擎未就绪，请确认 middleware 已启动。" +
                            "应用其余功能可用，报案接口将返回 503。", e.getMessage());
                }
                running = true;
            }

            @Override
            public void stop() {
                factory.shutdown();
                factory.awaitTermination(5, TimeUnit.SECONDS);
                runtime.setReady(false);
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TemporalRuntime temporalRuntime() {
        return new TemporalRuntime();
    }

    public static class TemporalRuntime {
        private volatile boolean ready;

        public boolean isReady() {
            return ready;
        }

        public void setReady(boolean ready) {
            this.ready = ready;
        }
    }
}