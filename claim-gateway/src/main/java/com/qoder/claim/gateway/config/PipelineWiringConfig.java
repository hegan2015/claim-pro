package com.qoder.claim.gateway.config;

import com.qoder.claim.common.event.DecisionEvent;
import com.qoder.claim.core.ledger.DoubleLedgerService;
import com.qoder.claim.core.ledger.PayResult;
import com.qoder.claim.mq.PaymentTransactionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.function.Consumer;

@Configuration
public class PipelineWiringConfig {

    /** 决策事件审计归档：RocketMQ 消费 → claim_decision 表（数仓/WORM 另路）。 */
    @Bean
    public Consumer<DecisionEvent> auditArchiveHandler(JdbcTemplate jdbc) {
        return e -> jdbc.update("INSERT INTO claim_decision(trace_id, case_no, step, actor, model_version, " +
                        "prompt_version, input_hash, output_json, confidence, prompt_tokens, completion_tokens, ts) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                e.getTraceId(), e.getCaseNo(), e.getStep(), e.getActor(), e.getModelVersion(),
                e.getPromptVersion(), e.getInputHash(), e.getOutputJson(), e.getConfidence(),
                e.getPromptTokens(), e.getCompletionTokens(), e.getTs());
    }

    /** 支付事务消息本地事务执行器：双账本+outbox 同事务。 */
    @Bean
    public PaymentTransactionService.LocalExecutor payLocalExecutor(DoubleLedgerService ledger) {
        return (idemKey, caseNo, batch, amountCents, fundChannel) -> {
            PayResult r = ledger.pay(caseNo, batch, amountCents, fundChannel);
            return r.getStatus();
        };
    }

    /** 支付事务回查：以 outbox 状态为准。 */
    @Bean
    public PaymentTransactionService.CheckExecutor payCheckExecutor(DoubleLedgerService ledger) {
        return idemKey -> {
            String status = ledger.outboxStatus(idemKey);
            return switch (status) {
                case "COMMITTED" -> "COMMIT";
                case "ROLLED_BACK" -> "ROLLBACK";
                default -> "UNKNOWN";
            };
        };
    }
}