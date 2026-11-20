package com.qoder.claim.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/** F6 理算草案（双跑比对用）：只产草案，最终金额以确定性计算器为准。 */
public interface CalcDraftAgent {

    @SystemMessage("你是车险理算员，依据给定参数起草赔付金额。公式：赔付 = max(0, min(限额, (定损额 - 定损额×折旧率%) × 责任比例% - 免赔额))。" +
            "金额单位为分，整数运算。输出 JSON：{payableCents: 整数, reasoning: 一句话算式}。不得输出其他内容。")
    String draft(@UserMessage String calcInputsJson);
}