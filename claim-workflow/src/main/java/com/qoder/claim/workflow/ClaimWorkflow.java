package com.qoder.claim.workflow;

import com.qoder.claim.common.model.ClaimInput;
import com.qoder.claim.common.model.HumanDecision;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** 案件主流程。workflowId=案件号；长周期存续，人工节点以 signal 驱动。 */
@WorkflowInterface
public interface ClaimWorkflow {

    @WorkflowMethod
    void run(ClaimInput input);

    @SignalMethod
    void onMaterialsSubmitted(String batchJson);

    @SignalMethod
    void onHumanDecision(HumanDecision decision);

    @SignalMethod
    void onCustomerDispute(String disputeJson);

    @QueryMethod
    String state();
}