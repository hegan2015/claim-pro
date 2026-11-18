package com.qoder.claim.common.port;

/** 工具网关端口：agent 的一切外部能力调用必须经此（鉴权/案件级ACL/限流/审计/幂等）。 */
public interface ToolGateway {

    /**
     * @param toolName 工具注册名
     * @param caseNo   案件上下文（ACL 依据）
     * @param argsJson 参数
     * @return 结果 JSON
     */
    String invoke(String toolName, String caseNo, String argsJson);
}