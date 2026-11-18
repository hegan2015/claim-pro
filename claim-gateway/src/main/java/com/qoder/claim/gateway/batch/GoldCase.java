package com.qoder.claim.gateway.batch;

import com.qoder.claim.core.rule.PolicySnapshot;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 金标案：人工标注的输入快照 + 期望裁决。expected 仅含需比对的字段（拒赔案不比对评分/通道）。 */
public class GoldCase {
    public String id;
    public String name;
    public PolicySnapshot policy;
    public LocalDate accidentDate;
    public String accidentType;
    public String description;
    public long estimateCents;
    public int priorClaims;
    public boolean flaggedRepairShop;
    public boolean lateNightReport;
    public double recognitionConfidence;
    public Map<String, Object> expected = new LinkedHashMap<>();
}