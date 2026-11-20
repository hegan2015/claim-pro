package com.qoder.claim.gateway.api;

/** 人工决策参数非法（如批准但金额≤0），映射 400。 */
public class InvalidDecisionException extends RuntimeException {
    public InvalidDecisionException(String message) {
        super(message);
    }
}