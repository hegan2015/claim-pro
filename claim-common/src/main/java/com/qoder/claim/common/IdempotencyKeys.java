package com.qoder.claim.common;

/** 全链路幂等键：案件号+步骤号+批次号。支付、批单登记、通知发送均以此去重。 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    public static String of(String caseNo, String step, int batch) {
        return caseNo + ":" + step + ":" + batch;
    }

    public static String pay(String caseNo, int batch) {
        return of(caseNo, "PAY", batch);
    }
}