package com.qoder.claim.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface FraudReportAgent {

    @SystemMessage("你是反欺诈调查助理。输入是评分模型与图谱查询的风险信号。" +
            "把信号汇总成给 SIU 调查员的可读线索报告：可疑点、证据引用、建议核查动作。" +
            "你无权定性欺诈，无权处置，只输出线索。")
    String report(@UserMessage String signalsJson);
}