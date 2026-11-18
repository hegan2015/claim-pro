package com.qoder.claim.workflow;

import com.qoder.claim.common.model.ClaimInput;
import com.qoder.claim.common.model.HumanDecision;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 工作流启动/信号入口（生产编排）。workflowId=案件号，天然幂等防重复报案。 */
@Service
@ConditionalOnProperty(prefix = "claim.temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowStarter {

    private final WorkflowClient client;
    private final TemporalProperties props;
    private final TemporalConfig.TemporalRuntime runtime;
    private final ClaimActivities activities;

    public WorkflowStarter(WorkflowClient client, TemporalProperties props, TemporalConfig.TemporalRuntime runtime,
                           ClaimActivities activities) {
        this.client = client;
        this.props = props;
        this.runtime = runtime;
        this.activities = activities;
    }

    public void start(ClaimInput input) {
        requireReady();
        activities.createCase(input); // 同步建案：API 返回前案件行必须已存在（activity 内幂等跳过）
        ClaimWorkflow stub = client.newWorkflowStub(ClaimWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(input.getCaseNo())
                        .setTaskQueue(props.getTaskQueue())
                        .build());
        WorkflowClient.start(stub::run, input);
    }

    public void signalMaterials(String caseNo) {
        requireReady();
        existing(caseNo).onMaterialsSubmitted("{}");
    }

    public void signalDecision(String caseNo, HumanDecision decision) {
        requireReady();
        existing(caseNo).onHumanDecision(decision);
    }

    public void signalDispute(String caseNo, String disputeJson) {
        requireReady();
        existing(caseNo).onCustomerDispute(disputeJson);
    }

    public String state(String caseNo) {
        requireReady();
        return existing(caseNo).state();
    }

    private ClaimWorkflow existing(String caseNo) {
        return client.newWorkflowStub(ClaimWorkflow.class, caseNo);
    }

    private void requireReady() {
        if (!runtime.isReady()) {
            throw new IllegalStateException("Temporal 未就绪：请先通过 ops-pro 启动 temporal 中间件");
        }
    }
}