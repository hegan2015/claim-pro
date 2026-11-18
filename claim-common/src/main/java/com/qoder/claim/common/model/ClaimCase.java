package com.qoder.claim.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

@Data
public class ClaimCase implements Serializable {
    private String caseNo;
    private String policyNo;
    private String org;
    private String insuranceType;
    private LocalDate accidentDate;
    private String accidentType;
    private CaseStatus status;
    private Channel channel;
    private long estimateCents;
    private long payableCents;
    private int riskScore;
    private double recognitionConfidence;
    private String rejectReason;
    private String description;
    private long createdAt;
    private long updatedAt;
}