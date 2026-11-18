package com.qoder.claim.mq;

import com.qoder.claim.common.event.DecisionEvent;
import com.qoder.claim.common.port.EventPublisher;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class MqConfig {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "claim.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DefaultMQProducer claimProducer(MqProperties props) {
        DefaultMQProducer p = new DefaultMQProducer(props.getProducerGroup());
        p.setNamesrvAddr(props.getNamesrv());
        p.setSendMsgTimeout(5000);
        p.setRetryTimesWhenSendFailed(2);
        return p;
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "claim.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TransactionMQProducer payTxProducer(MqProperties props) {
        TransactionMQProducer p = new TransactionMQProducer(props.getTxProducerGroup());
        p.setNamesrvAddr(props.getNamesrv());
        return p;
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public EventPublisher rocketMqEventPublisher(DefaultMQProducer producer, MqProperties props) {
        return new RocketMqEventPublisher(producer, props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.mq", name = "enabled", havingValue = "false")
    public EventPublisher localLogEventPublisher(ObjectProvider<Consumer<DecisionEvent>> auditConsumer) {
        return new LocalLogEventPublisher(auditConsumer.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PaymentTransactionService paymentTransactionService(
            TransactionMQProducer payTxProducer, MqProperties props,
            PaymentTransactionService.LocalExecutor localExecutor,
            PaymentTransactionService.CheckExecutor checkExecutor) {
        return new PaymentTransactionService(payTxProducer, props, localExecutor, checkExecutor);
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "claim.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DefaultMQPushConsumer decisionEventConsumer(MqProperties props, DecisionEventConsumer listener) {
        DefaultMQPushConsumer c = new DefaultMQPushConsumer(props.getConsumerGroup());
        c.setNamesrvAddr(props.getNamesrv());
        try {
            c.subscribe(props.getDecisionEventsTopic(), "*");
        } catch (Exception e) {
            throw new IllegalStateException("订阅决策事件 topic 失败", e);
        }
        c.registerMessageListener(listener);
        return c;
    }
}