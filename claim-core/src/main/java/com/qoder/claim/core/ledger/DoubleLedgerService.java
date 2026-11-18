package com.qoder.claim.core.ledger;

import com.qoder.claim.common.IdempotencyKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 双账本支付（确定性核心，LLM 禁入）。
 * 业务账（案件维度）与资金账（渠道维度）同库同事务落库，pay_outbox 为
 * RocketMQ 事务消息的本地事务依据与回查依据。幂等键=案件号+赔付批次。
 */
@Service
public class DoubleLedgerService {

    private final JdbcTemplate jdbc;

    public DoubleLedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PayResult pay(String caseNo, int batch, long amountCents, String fundChannel) {
        String key = IdempotencyKeys.pay(caseNo, batch);
        List<Map<String, Object>> existing =
                jdbc.queryForList("SELECT status, amount_cents FROM pay_outbox WHERE idem_key=?", key);
        PayResult r = new PayResult();
        r.setIdempotencyKey(key);
        if (!existing.isEmpty()) {
            r.setAlreadyProcessed(true);
            r.setAmountCents(((Number) existing.get(0).get("amount_cents")).longValue());
            r.setStatus((String) existing.get(0).get("status"));
            return r;
        }
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO ledger_business(case_no, batch, direction, amount_cents, ts) VALUES(?,?,?,?,?)",
                caseNo, batch, "PAYABLE", amountCents, now);
        jdbc.update("INSERT INTO ledger_fund(case_no, batch, fund_channel, direction, amount_cents, ts) VALUES(?,?,?,?,?,?)",
                caseNo, batch, fundChannel, "OUT", amountCents, now);
        jdbc.update("INSERT INTO pay_outbox(idem_key, case_no, batch, amount_cents, fund_channel, status, ts) VALUES(?,?,?,?,?,?,?)",
                key, caseNo, batch, amountCents, fundChannel, "COMMITTED", now);
        r.setAmountCents(amountCents);
        r.setStatus("COMMITTED");
        return r;
    }

    /** RocketMQ 事务回查依据：outbox 状态。 */
    public String outboxStatus(String idemKey) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT status FROM pay_outbox WHERE idem_key=?", idemKey);
        return rows.isEmpty() ? "UNKNOWN" : (String) rows.get(0).get("status");
    }

    public List<Map<String, Object>> businessEntries(String caseNo) {
        return jdbc.queryForList("SELECT * FROM ledger_business WHERE case_no=? ORDER BY ts", caseNo);
    }

    public List<Map<String, Object>> fundEntries(String caseNo) {
        return jdbc.queryForList("SELECT * FROM ledger_fund WHERE case_no=? ORDER BY ts", caseNo);
    }
}