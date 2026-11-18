package com.qoder.claim.core.rule;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 保单快照：由核心系统适配器加载，规则引擎只读。 */
@Data
public class PolicySnapshot {
    private String policyNo;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private List<String> coverageTypes = new ArrayList<>();
    private long limitCents;
    private long deductibleCents;
    private int depreciationPercent;
    private String clauseVersion;
}