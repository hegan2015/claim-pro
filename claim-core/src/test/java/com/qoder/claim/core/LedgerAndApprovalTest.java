package com.qoder.claim.core;

import com.qoder.claim.common.model.Channel;
import com.qoder.claim.core.approval.ApprovalService;
import com.qoder.claim.core.ledger.DoubleLedgerService;
import com.qoder.claim.core.ledger.PayResult;
import com.qoder.claim.core.ledger.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class LedgerAndApprovalTest {

    private JdbcTemplate jdbc;
    private DoubleLedgerService ledger;
    private ApprovalService approval;
    private ReconciliationService recon;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:ledger" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ledger_business(id BIGINT AUTO_INCREMENT PRIMARY KEY, case_no VARCHAR(64), batch INT, direction VARCHAR(16), amount_cents BIGINT, ts BIGINT)");
        jdbc.execute("CREATE TABLE ledger_fund(id BIGINT AUTO_INCREMENT PRIMARY KEY, case_no VARCHAR(64), batch INT, fund_channel VARCHAR(32), direction VARCHAR(16), amount_cents BIGINT, ts BIGINT)");
        jdbc.execute("CREATE TABLE pay_outbox(idem_key VARCHAR(128) PRIMARY KEY, case_no VARCHAR(64), batch INT, amount_cents BIGINT, fund_channel VARCHAR(32), status VARCHAR(16), ts BIGINT)");
        jdbc.execute("CREATE TABLE claim_approval(id BIGINT AUTO_INCREMENT PRIMARY KEY, case_no VARCHAR(64), step VARCHAR(32), operator VARCHAR(64), approved INT, comment VARCHAR(512), amount_cents BIGINT, ts BIGINT)");
        jdbc.execute("CREATE TABLE recon_diff(id BIGINT AUTO_INCREMENT PRIMARY KEY, case_no VARCHAR(64), batch INT, biz_amount BIGINT, fund_amount BIGINT, status VARCHAR(16), ts BIGINT)");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ledger = new DoubleLedgerService(jdbc) {
            @Override
            public PayResult pay(String caseNo, int batch, long amountCents, String fundChannel) {
                return tx.execute(s -> super.pay(caseNo, batch, amountCents, fundChannel));
            }
        };
        approval = new ApprovalService(jdbc);
        recon = new ReconciliationService(jdbc);
    }

    @Test
    void payIsIdempotent() {
        PayResult first = ledger.pay("C1", 1, 5800_00, "BANK_DIRECT");
        assertFalse(first.isAlreadyProcessed());
        PayResult second = ledger.pay("C1", 1, 5800_00, "BANK_DIRECT");
        assertTrue(second.isAlreadyProcessed());
        assertEquals(5800_00, second.getAmountCents());
        assertEquals(1, ledger.businessEntries("C1").size());
        assertEquals(1, ledger.fundEntries("C1").size());
        assertEquals("COMMITTED", ledger.outboxStatus(first.getIdempotencyKey()));
    }

    @Test
    void reconciliationClean() {
        ledger.pay("C2", 1, 100_00, "BANK_DIRECT");
        assertEquals(0, recon.reconcile());
    }

    @Test
    void reconciliationDetectsMissingFundEntry() {
        ledger.pay("C3", 1, 100_00, "BANK_DIRECT");
        jdbc.update("DELETE FROM ledger_fund WHERE case_no='C3'");
        assertEquals(1, recon.reconcile());
        assertEquals(1, recon.openDiffs().size());
    }

    @Test
    void approvalTiers() {
        assertEquals(0, approval.requiredApprovers(4000_00, Channel.FAST));
        assertEquals(1, approval.requiredApprovers(30_000_00, Channel.STANDARD));
        assertEquals(2, approval.requiredApprovers(150_000_00, Channel.INVESTIGATE));
        approval.record("C4", "APPROVE", "op1", true, "ok");
        assertEquals(1, approval.approvalCount("C4", "APPROVE"));
        assertFalse(approval.hasRejection("C4", "APPROVE"));
        approval.record("C4", "APPROVE", "op2", false, "金额异议");
        assertTrue(approval.hasRejection("C4", "APPROVE"));
    }
}