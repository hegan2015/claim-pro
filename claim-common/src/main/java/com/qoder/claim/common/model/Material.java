package com.qoder.claim.common.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class Material implements Serializable {
    private String materialId;
    private String caseNo;
    private String type;
    private String storageKey;
    private String sha256;
    private double confidence;
}