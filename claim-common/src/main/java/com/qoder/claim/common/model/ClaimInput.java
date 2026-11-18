package com.qoder.claim.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

@Data
public class ClaimInput implements Serializable {
    private String caseNo;
    private String policyNo;
    private String org;
    private String insuranceType;
    private LocalDate accidentDate;
    private String accidentType;
    private String reporterPhone;
    private String description;
}