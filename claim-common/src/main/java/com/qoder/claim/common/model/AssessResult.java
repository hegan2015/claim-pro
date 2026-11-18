package com.qoder.claim.common.model;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AssessResult implements Serializable {
    private String caseNo;
    private List<Item> items = new ArrayList<>();
    private long estimateCents;
    private List<String> evidenceRefs = new ArrayList<>();

    @Data
    public static class Item implements Serializable {
        private String partName;
        private String action;
        private long priceCents;
        private String priceSourceId;
    }
}