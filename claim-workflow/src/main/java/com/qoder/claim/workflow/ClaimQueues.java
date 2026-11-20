package com.qoder.claim.workflow;

/** DESIGN 4.1 六队列物理隔离：识别/复核/定损等大流量或需隔离的 activity 独立队列独立限流。 */
public final class ClaimQueues {

    public static final String CORE = "claim-core";       // 规则/计算器/审批/支付（确定性核心，LLM 禁入）+ 工作流
    public static final String INTAKE = "claim-intake";   // 受理/沟通
    public static final String DOC = "claim-doc";         // 材料识别（大流量，独立限流）
    public static final String ASSESS = "claim-assess";   // 定损/理算建议
    public static final String REVIEW = "claim-review";   // 独立复核（只读工具，物理隔离）
    public static final String FRAUD = "claim-fraud";     // 欺诈汇总

    private ClaimQueues() {
    }
}