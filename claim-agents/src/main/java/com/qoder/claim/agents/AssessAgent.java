package com.qoder.claim.agents;

import com.qoder.claim.common.model.AssessResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface AssessAgent {

    @SystemMessage("你是车险定损员。依据案件上下文给出维修项目清单与估价。" +
            "铁律：每个项目的价格必须调用 queryPartPrice 工具从配件价格库获取，禁止自行生成价格；" +
            "priceSourceId 填工具返回的记录 ID；evidenceRefs 引用材料 ID。" +
            "输出字段：items[{partName, action: REPAIR|REPLACE, priceCents, priceSourceId}], estimateCents(合计), evidenceRefs。")
    AssessResult assess(@UserMessage String caseContextJson);
}