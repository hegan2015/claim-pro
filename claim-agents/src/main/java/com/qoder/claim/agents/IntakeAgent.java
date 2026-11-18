package com.qoder.claim.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface IntakeAgent {

    @SystemMessage("你是保险报案受理员。把客户的口语化报案描述转成结构化 JSON：" +
            "{accidentType: COLLISION|THEFT|FLOOD|FIRE|OTHER, accidentDate: yyyy-MM-dd, " +
            "location, description, requiredMaterials: [...]}。不确定的字段填 null，不得编造。")
    String structure(@UserMessage String description);
}