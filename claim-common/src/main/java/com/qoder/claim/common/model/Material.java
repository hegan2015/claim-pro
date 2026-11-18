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
    private String fileName;
    private String contentType;
    /** 有文件本体时为正数字节数；null 表示仅元数据登记（演示/存量材料）。 */
    private Long sizeBytes;
}