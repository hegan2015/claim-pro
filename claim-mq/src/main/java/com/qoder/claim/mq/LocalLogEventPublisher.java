package com.qoder.claim.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.claim.common.event.DecisionEvent;
import com.qoder.claim.common.port.EventPublisher;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/** claim.mq.enabled=false 时的显式开发模式：事件写本地日志并环回审计归档，启动即告警，非静默 mock。 */
@Slf4j
public class LocalLogEventPublisher implements EventPublisher {

    private final ObjectMapper json = new ObjectMapper();
    private final Consumer<DecisionEvent> auditLoopback;

    public LocalLogEventPublisher(Consumer<DecisionEvent> auditLoopback) {
        this.auditLoopback = auditLoopback;
        log.warn("RocketMQ 已禁用（claim.mq.enabled=false）：决策事件写本地日志并环回归档，生产环境禁止此模式");
    }

    @Override
    public void publishDecision(DecisionEvent event) {
        try {
            log.info("[LOCAL-EVENT] {}", json.writeValueAsString(event));
        } catch (Exception e) {
            log.info("[LOCAL-EVENT] case={} step={}", event.getCaseNo(), event.getStep());
        }
        if (auditLoopback != null) {
            auditLoopback.accept(event); // 本地环回：模拟 MQ 消费→claim_decision 归档
        }
    }

    @Override
    public void publishNotify(String caseNo, String kind, String payloadJson) {
        log.info("[LOCAL-NOTIFY] case={} kind={} payload={}", caseNo, kind, payloadJson);
    }
}