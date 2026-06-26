package com.example.lms.llm.gateway;

public class LlmGatewayException extends IllegalStateException {

    private final LlmFailureClass failureClass;

    public LlmGatewayException(String message, LlmFailureClass failureClass) {
        super(message);
        this.failureClass = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
    }

    public LlmFailureClass failureClass() {
        return failureClass;
    }
}
