package com.qoder.claim.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.claim.common.event.DecisionEvent;
import com.qoder.claim.common.port.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;

/** 决策/审计事件：RocketMQ 顺序消息，按案件号分区，保证单案件决策时序。 */
@Slf4j
public class RocketMqEventPublisher implements EventPublisher {

    private final DefaultMQProducer producer;
    private final MqProperties props;
    private final ObjectMapper json = new ObjectMapper();

    public RocketMqEventPublisher(DefaultMQProducer producer, MqProperties props) {
        this.producer = producer;
        this.props = props;
    }

    @Override
    public void publishDecision(DecisionEvent event) {
        try {
            Message msg = new Message(props.getDecisionEventsTopic(), "DECISION",
                    event.getCaseNo(), json.writeValueAsBytes(event));
            SendResult r = producer.send(msg,
                    (mqs, m, arg) -> mqs.get(Math.abs(arg.hashCode()) % mqs.size()),
                    event.getCaseNo());
            log.debug("决策事件已发送 case={} step={} msgId={}", event.getCaseNo(), event.getStep(), r.getMsgId());
        } catch (Exception e) {
            // 审计事件不允许静默丢失：抛出由调用方（workflow activity）重试
            throw new IllegalStateException("决策事件发送失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void publishNotify(String caseNo, String kind, String payloadJson) {
        try {
            Message msg = new Message(props.getNotifyTopic(), kind, caseNo,
                    payloadJson.getBytes(StandardCharsets.UTF_8));
            producer.send(msg);
        } catch (Exception e) {
            throw new IllegalStateException("通知消息发送失败: " + e.getMessage(), e);
        }
    }
}