package com.qoder.claim.llm;

/** 模型网关异常。携带 HTTP 状态与"已出流/熔断"标记，供重试分类。 */
public class LlmGatewayException extends RuntimeException {

    private int httpStatus;
    private boolean streamed;
    private boolean circuit;

    public LlmGatewayException(String message) {
        super(message);
    }

    public LlmGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    public LlmGatewayException withHttpStatus(int status) {
        this.httpStatus = status;
        return this;
    }

    public LlmGatewayException markStreamed() {
        this.streamed = true;
        return this;
    }

    public LlmGatewayException markCircuit() {
        this.circuit = true;
        return this;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isStreamed() {
        return streamed;
    }

    public boolean isCircuit() {
        return circuit;
    }
}