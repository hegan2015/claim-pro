package com.qoder.claim.core.rule;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CoverageDecision {
    private boolean covered;
    private List<String> hitClauses = new ArrayList<>();
    private List<String> exclusions = new ArrayList<>();
    private String clauseVersion;
    private String explanationKey;
}