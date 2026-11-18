package com.qoder.claim.common.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class CalcResult implements Serializable {
    private String caseNo;
    private long estimateCents;
    private long deductibleCents;
    private long depreciationCents;
    private long limitCents;
    private int liabilityPercent;
    private long payableCents;
    private String breakdown;
}