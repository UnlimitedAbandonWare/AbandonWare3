package com.example.lms.llm.gateway;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmGatewayFailureClassifierTest {

    private final LlmGatewayFailureClassifier classifier = new LlmGatewayFailureClassifier();

    @Test
    void classifiesRateLimitAndAuthWithoutHardBreakerForNeutralCancel() {
        assertEquals(LlmFailureClass.RATE_LIMIT_COOLDOWN,
                classifier.classify(WebClientResponseException.create(
                        429, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));
        assertEquals(LlmFailureClass.AUTH_MISSING,
                classifier.classify(WebClientResponseException.create(
                        401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));
        assertEquals(LlmFailureClass.CANCELLED_NEUTRAL,
                classifier.classify(new CancellationException("cancelled by caller")));
    }

    @Test
    void classifiesGatewayTimeoutAsSoftTimeout() {
        assertEquals(LlmFailureClass.TIMEOUT_SOFT,
                classifier.classify(WebClientResponseException.create(
                        504, "Gateway Timeout", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));
    }

    @Test
    void classifiesOpenCircuitBreakerAsSoftCircuitOpen() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("llm-gateway-test");
        CallNotPermittedException failure = CallNotPermittedException.createCallNotPermittedException(breaker);

        assertEquals(LlmFailureClass.SOFT_CIRCUIT_OPEN, classifier.classify(failure));
        assertEquals(LlmFailureClass.SOFT_CIRCUIT_OPEN,
                classifier.classify(new RuntimeException("circuit breaker is OPEN for llm gateway")));
    }

    @Test
    void classifiesRouteBlockingMessages() {
        assertEquals(LlmFailureClass.MODEL_MISSING, classifier.classify(new RuntimeException("model not found")));
        assertEquals(LlmFailureClass.VRAM_OOM, classifier.classify(new RuntimeException("CUDA VRAM OOM")));
        assertEquals(LlmFailureClass.TIMEOUT_SOFT, classifier.classify(new RuntimeException("request timeout")));
    }
}
