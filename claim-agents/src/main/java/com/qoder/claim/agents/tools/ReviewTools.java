package com.qoder.claim.agents.tools;

import com.qoder.claim.common.port.ToolGateway;
import com.qoder.claim.runtime.CaseContext;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/** 复核工具集：只读。复核 agent 与定损/理算上下文物理隔离，工具权限亦独立。 */
@Component
public class ReviewTools {

    private final ToolGateway gateway;

    public ReviewTools(ToolGateway gateway) {
        this.gateway = gateway;
    }

    @Tool("读取案件材料清单与识别结果（只读）")
    public String caseMaterials() {
        return gateway.invoke("case.materials", CaseContext.get(), "{}");
    }

    @Tool("查询同保单历史案件数量与金额（只读）")
    public String claimHistory() {
        return gateway.invoke("history.count", CaseContext.get(), "{}");
    }
}