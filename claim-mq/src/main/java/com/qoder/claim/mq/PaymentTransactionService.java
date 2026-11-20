package com.qoder.claim.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 支付事务消息（半消息）：
 * 1. 发半消息 → 2. 执行本地事务（双账本+outbox 同事务）→ 3. COMMIT/ROLLBACK
 * 4. broker 回查 → 查 outbox 状态补决策。
 * 本地事务与回查逻辑由上层注入（mq 模块不依赖 core）。
 */
@Slf4j
public class PaymentTransactionService {

    public interface LocalExecutor {
        /** @return COMMIT / ROLLBACK / UNKNOWN */
        String execute(String idemKey, String caseNo, int batch, long amountCents, String fundChannel);
    }

    public interface CheckExecutor {
        /** @return COMMIT / ROLLBACK / UNKNOWN（依据 outbox 状态） */
        String check(String idemKey);
    }

    private final TransactionMQProducer producer;
    private final MqProperties props;

    public PaymentTransactionService(TransactionMQProducer producer, MqProperties props,
                                     LocalExecutor localExecutor, CheckExecutor checkExecutor) {
        this.producer = producer;
        this.props = props;
        ExecutorService executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200), r -> new Thread(r, "pay-tx-check"));
        producer.setExecutorService(executor);
        producer.setTransactionListener(newListener(localExecutor, checkExecutor));
    }

    static TransactionListener newListener(LocalExecutor localExecutor, CheckExecutor checkExecutor) {
        return new TransactionListener() {
            @Override
            public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
                PayArgs a = (PayArgs) arg;
                try {
                    return state(localExecutor.execute(a.idemKey(), a.caseNo(), a.batch(), a.amountCents(), a.fundChannel()));
                } catch (Exception e) {
                    log.error("支付本地事务异常 idem={}", a.idemKey(), e);
                    return LocalTransactionState.UNKNOW;
                }
            }

            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                String idemKey = msg.getKeys();
                String status = checkExecutor.check(idemKey);
                log.info("支付事务回查 idem={} status={}", idemKey, status);
                return state(status);
            }
        };
    }

    private static LocalTransactionState state(String s) {
        return switch (s == null ? "UNKNOWN" : s) {
            case "COMMITTED", "COMMIT" -> LocalTransactionState.COMMIT_MESSAGE;
            case "ROLLED_BACK", "ROLLBACK" -> LocalTransactionState.ROLLBACK_MESSAGE;
            default -> LocalTransactionState.UNKNOW;
        };
    }

    public void sendPayTransaction(String idemKey, String caseNo, int batch,
                                   long amountCents, String fundChannel) throws Exception {
        PayArgs args = new PayArgs(idemKey, caseNo, batch, amountCents, fundChannel);
        String body = String.format("{\"idemKey\":\"%s\",\"caseNo\":\"%s\",\"batch\":%d,\"amountCents\":%d,\"fundChannel\":\"%s\"}",
                idemKey, caseNo, batch, amountCents, fundChannel);
        Message msg = new Message(props.getPayTxTopic(), "PAY", idemKey,
                body.getBytes(StandardCharsets.UTF_8));
        producer.sendMessageInTransaction(msg, args);
    }

    record PayArgs(String idemKey, String caseNo, int batch, long amountCents, String fundChannel) {
    }
}