package com.qoder.claim.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "claim.mq")
public class MqProperties {

    /** 显式开关：关闭时事件仅写本地日志（开发模式，启动时明确告警），不做静默 mock。 */
    private boolean enabled = true;
    private String namesrv = "127.0.0.1:9876";
    private String producerGroup = "claim-producer";
    private String txProducerGroup = "claim-pay-tx-producer";
    private String consumerGroup = "claim-audit-consumer";
    private String decisionEventsTopic = "claim-decision-events";
    private String payTxTopic = "claim-pay-tx";
    private String notifyTopic = "claim-notify";
    private String feedbackTopic = "claim-feedback";
}