package com.example.lms.llm.gateway;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

@Component
public class LlmGatewayFailureClassifier {

    public LlmFailureClass classify(Throwable failure) {
        if (failure == null) {
            return LlmFailureClass.NONE;
        }
        Throwable t = failure;
        while (t != null) {
            LlmFailureClass direct = classifyOne(t);
            if (direct != LlmFailureClass.UNKNOWN) {
                return direct;
            }
            t = t.getCause();
        }
        return classifyMessage(failure.toString());
    }

    public LlmFailureClass classifyEmptyAfterException(Throwable failure) {
        LlmFailureClass classified = classify(failure);
        return classified == LlmFailureClass.NONE ? LlmFailureClass.UNKNOWN : classified;
    }

    private static LlmFailureClass classifyOne(Throwable t) {
        if (t instanceof CancellationException) {
            return LlmFailureClass.CANCELLED_NEUTRAL;
        }
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return LlmFailureClass.CANCELLED_NEUTRAL;
        }
        if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
            return LlmFailureClass.TIMEOUT_SOFT;
        }
        if (t instanceof CallNotPermittedException) {
            return LlmFailureClass.SOFT_CIRCUIT_OPEN;
        }
        if (t instanceof ConnectException || t instanceof UnknownHostException) {
            return LlmFailureClass.HEALTH_DOWN;
        }
        if (t instanceof WebClientResponseException wcre) {
            int status = wcre.getRawStatusCode();
            if (status == 401 || status == 403) {
                return LlmFailureClass.AUTH_MISSING;
            }
            if (status == 404) {
                return LlmFailureClass.MODEL_MISSING;
            }
            if (status == 429) {
                return LlmFailureClass.RATE_LIMIT_COOLDOWN;
            }
            if (status == 504) {
                return LlmFailureClass.TIMEOUT_SOFT;
            }
            if (status >= 500) {
                return LlmFailureClass.HEALTH_DOWN;
            }
            if (status >= 400) {
                return classifyMessage(wcre.getResponseBodyAsString());
            }
        }
        return classifyMessage(t.getMessage());
    }

    private static LlmFailureClass classifyMessage(String message) {
        if (message == null || message.isBlank()) {
            return LlmFailureClass.UNKNOWN;
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("cancel")) {
            return LlmFailureClass.CANCELLED_NEUTRAL;
        }
        if (m.contains("rate limit") || m.contains("too many requests") || m.contains("http_429") || m.contains("429")) {
            return LlmFailureClass.RATE_LIMIT_COOLDOWN;
        }
        if (m.contains("timed out") || m.contains("timeout")) {
            return LlmFailureClass.TIMEOUT_SOFT;
        }
        if ((m.contains("circuit") || m.contains("breaker")) && (m.contains("open") || m.contains("not permitted"))) {
            return LlmFailureClass.SOFT_CIRCUIT_OPEN;
        }
        if (m.contains("unauthorized") || m.contains("forbidden") || m.contains("api key") || m.contains("auth_missing")
                || m.contains("missing key") || m.contains("owner token")) {
            return LlmFailureClass.AUTH_MISSING;
        }
        if (m.contains("model_not_found") || m.contains("model not found") || m.contains("not found")) {
            return LlmFailureClass.MODEL_MISSING;
        }
        if (m.contains("oom") || m.contains("out of memory") || m.contains("vram")) {
            return LlmFailureClass.VRAM_OOM;
        }
        if (m.contains("connection refused") || m.contains("unknownhost") || m.contains("health_down")) {
            return LlmFailureClass.HEALTH_DOWN;
        }
        if (m.contains("stream")) {
            return LlmFailureClass.STREAM_ERROR;
        }
        return LlmFailureClass.UNKNOWN;
    }
}
