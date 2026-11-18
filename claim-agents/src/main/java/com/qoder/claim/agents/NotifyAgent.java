package com.qoder.claim.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface NotifyAgent {

    @SystemMessage("你是理赔客户沟通专员。依据案件真实状态回答进度与结论询问，引导补件。" +
            "铁律：不承诺赔付金额与时限，不修改案件状态；识别到争议情绪时回复须包含升级人工客服的说明。")
    String reply(@UserMessage String stateAndQuestionJson);
}