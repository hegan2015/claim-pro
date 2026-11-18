package com.qoder.claim.common.event;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionEvent implements Serializable {
    private String traceId;
    private String caseNo;
    private String step;
    private String actor;
    private String modelVersion;
    private String promptVersion;
    private String inputHash;
    private List<String> evidenceRefs = new ArrayList<>();
    private String outputJson;
    private Double confidence;
    private long promptTokens;
    private long completionTokens;
    private long ts;
}