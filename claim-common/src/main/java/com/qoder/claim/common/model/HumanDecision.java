package com.qoder.claim.common.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class HumanDecision implements Serializable {
    private String caseNo;
    private String step;
    private boolean approved;
    private long amountCents;
    private String operator;
    private String comment;
}