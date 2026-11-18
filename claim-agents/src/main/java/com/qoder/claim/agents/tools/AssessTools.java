package com.qoder.claim.agents.tools;

import com.qoder.claim.common.port.ToolGateway;
import com.qoder.claim.runtime.CaseContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/** 定损工具集：@Tool 只是 schema 语法糖，方法体内强制走工具网关（鉴权/审计/幂等）。 */
@Component
public class AssessTools {

    private final ToolGateway gateway;

    public AssessTools(ToolGateway gateway) {
        this.gateway = gateway;
    }

    @Tool("按车型与配件名查询官方配件价格，返回 {priceCents, priceSourceId}。价格只能来自本工具。")
    public String queryPartPrice(@P("车型编码，如 VW-LAVIDA-2023") String modelCode,
                                 @P("配件名称，如 前保险杠") String partName) {
        return gateway.invoke("parts.price", CaseContext.get(),
                "{\"modelCode\":\"" + modelCode + "\",\"partName\":\"" + partName + "\"}");
    }

    @Tool("查询案件关联保单信息，返回保单快照 JSON")
    public String queryPolicy() {
        return gateway.invoke("policy.query", CaseContext.get(), "{}");
    }
}