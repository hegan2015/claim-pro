package com.qoder.claim.core.ledger;

import lombok.Data;

@Data
public class PayResult {
    private String idempotencyKey;
    private long amountCents;
    private boolean alreadyProcessed;
    private String status;
}