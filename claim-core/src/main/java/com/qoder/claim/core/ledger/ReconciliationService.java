package com.qoder.claim.core.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 日终对账：业务账 vs 资金账按案件+批次比对，差异落 recon_diff 供差错处理台。
 * 生产另有两层：本系统 vs 核心系统、账本 vs 银行回执（XXL-Job 调度）。
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int reconcile() {
        List<Map<String, Object>> diffs = jdbc.queryForList(
                "SELECT b.case_no, b.batch, b.amount_cents AS biz_amount, f.amount_cents AS fund_amount " +
                "FROM ledger_business b LEFT JOIN ledger_fund f " +
                "ON b.case_no=f.case_no AND b.batch=f.batch " +
                "WHERE f.amount_cents IS NULL OR b.amount_cents <> f.amount_cents");
        long now = System.currentTimeMillis();
        for (Map<String, Object> d : diffs) {
            jdbc.update("INSERT INTO recon_diff(case_no, batch, biz_amount, fund_amount, status, ts) VALUES(?,?,?,?,?,?)",
                    d.get("case_no"), d.get("batch"), d.get("biz_amount"), d.get("fund_amount"), "OPEN", now);
        }
        return diffs.size();
    }

    public List<Map<String, Object>> openDiffs() {
        return jdbc.queryForList("SELECT * FROM recon_diff WHERE status='OPEN' ORDER BY ts");
    }
}