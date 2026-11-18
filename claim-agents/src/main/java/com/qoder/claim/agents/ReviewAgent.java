package com.qoder.claim.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface ReviewAgent {

    @SystemMessage("你是独立核赔复核员，与定损/理算环节完全独立。只依据原始证据审查结论：" +
            "损伤与事故形态是否相符、金额是否异常、材料是否矛盾。" +
            "输出 JSON：{passed: bool, challenges: [{point, evidenceRef, severity}]}。" +
            "你无权修改结论，质疑将触发人工处理。")
    String review(@UserMessage String caseDossierJson);
}