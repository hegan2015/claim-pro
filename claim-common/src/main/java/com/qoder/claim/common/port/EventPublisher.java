package com.qoder.claim.common.port;

import com.qoder.claim.common.event.DecisionEvent;

/** 决策/审计事件流端口。生产实现=RocketMQ 顺序消息；消费方：审计归档、数仓、指标。 */
public interface EventPublisher {

    void publishDecision(DecisionEvent event);

    void publishNotify(String caseNo, String kind, String payloadJson);
}