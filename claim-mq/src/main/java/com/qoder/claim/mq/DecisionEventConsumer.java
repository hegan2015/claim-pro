package com.qoder.claim.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.claim.common.event.DecisionEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/** 决策事件消费：分发给审计归档处理器（由 gateway 装配，落库+WORM）。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "claim.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DecisionEventConsumer implements MessageListenerConcurrently {

    private final ObjectMapper json = new ObjectMapper();
    private final Consumer<DecisionEvent> handler;

    public DecisionEventConsumer(Consumer<DecisionEvent> auditArchiveHandler) {
        this.handler = auditArchiveHandler;
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
        for (MessageExt m : msgs) {
            try {
                DecisionEvent e = json.readValue(m.getBody(), DecisionEvent.class);
                handler.accept(e);
            } catch (Exception e) {
                log.error("决策事件消费失败 msgId={}，稍后重投", m.getMsgId(), e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}