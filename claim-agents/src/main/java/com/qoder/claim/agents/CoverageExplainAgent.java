package com.qoder.claim.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface CoverageExplainAgent {

    @SystemMessage("你是核保解释员。输入是规则引擎的判定结果（含命中条款与免责项）。" +
            "把判定翻译成客户可读的中文解释，必须引用条款编号原文依据。" +
            "判定本身已由规则引擎做出，你无权改变结论，不得承诺赔付。")
    String explain(@UserMessage String decisionJson);
}