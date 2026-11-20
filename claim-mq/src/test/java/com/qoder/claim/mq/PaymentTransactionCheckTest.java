package com.qoder.claim.mq;

import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 支付事务消息回查演练：broker 回查决策以 outbox 状态为准。
 * 覆盖 COMMIT/ROLLBACK/UNKNOWN 三态 + 本地事务异常→UNKNOWN（等待回查补决策）。
 */
class PaymentTransactionCheckTest {

    private PaymentTransactionService.CheckExecutor check;
    private PaymentTransactionService.LocalExecutor local;
    private org.apache.rocketmq.client.producer.TransactionListener listener;

    @BeforeEach
    void setup() {
        check = mock(PaymentTransactionService.CheckExecutor.class);
        local = mock(PaymentTransactionService.LocalExecutor.class);
        listener = PaymentTransactionService.newListener(local, check);
    }

    private static MessageExt msg(String idemKey) {
        MessageExt m = new MessageExt();
        m.setKeys(idemKey);
        m.setBody("{}".getBytes(StandardCharsets.UTF_8));
        return m;
    }

    @Test
    void checkBackCommitWhenOutboxCommitted() {
        when(check.check("CASE-1:1")).thenReturn("COMMIT");
        assertEquals(LocalTransactionState.COMMIT_MESSAGE, listener.checkLocalTransaction(msg("CASE-1:1")));
    }

    @Test
    void checkBackRollbackWhenOutboxRolledBack() {
        when(check.check("CASE-2:1")).thenReturn("ROLLBACK");
        assertEquals(LocalTransactionState.ROLLBACK_MESSAGE, listener.checkLocalTransaction(msg("CASE-2:1")));
    }

    @Test
    void checkBackUnknownWhenOutboxMissing() {
        when(check.check("CASE-3:1")).thenReturn("UNKNOWN");
        assertEquals(LocalTransactionState.UNKNOW, listener.checkLocalTransaction(msg("CASE-3:1")));
    }

    @Test
    void localTransactionExceptionYieldsUnknownForLaterCheckBack() throws Exception {
        when(local.execute(anyString(), anyString(), anyInt(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        Message m = new Message("topic", "PAY", "CASE-4:1", "{}".getBytes(StandardCharsets.UTF_8));
        Object args = new PaymentTransactionService.PayArgs("CASE-4:1", "CASE-4", 1, 100L, "BANK_DIRECT");
        assertEquals(LocalTransactionState.UNKNOW, listener.executeLocalTransaction(m, args));
        // 回查补决策：outbox 无记录 → UNKNOWN 继续回查；落库后 → COMMIT
        when(check.check("CASE-4:1")).thenReturn("UNKNOWN");
        assertEquals(LocalTransactionState.UNKNOW, listener.checkLocalTransaction(msg("CASE-4:1")));
        when(check.check("CASE-4:1")).thenReturn("COMMIT");
        assertEquals(LocalTransactionState.COMMIT_MESSAGE, listener.checkLocalTransaction(msg("CASE-4:1")));
    }
}