package com.example.lms.llm.gateway;

import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.SseEventPublisher;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LlmGatewayBreadcrumbPublisher {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayBreadcrumbPublisher.class);

    private final ObjectProvider<SseEventPublisher> ssePublisherProvider;

    public LlmGatewayBreadcrumbPublisher(ObjectProvider<SseEventPublisher> ssePublisherProvider) {
        this.ssePublisherProvider = ssePublisherProvider;
    }

    public void publishEligibility(RoutingEligibility eligibility) {
        if (eligibility == null) {
            return;
        }
        Map<String, Object> payload = safePayload(eligibility.asBreadcrumb());
        trace("llm.gateway.route.", payload);
        emit("llm.gateway.route", payload);
    }

    public void publishFallback(String fromKey, String toKey, LlmFailureClass failureClass, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromKey", fromKey);
        payload.put("toKey", toKey);
        payload.put("failureClass", failureClass == null ? LlmFailureClass.UNKNOWN.name() : failureClass.name());
        payload.put("reason", reason);
        payload = safePayload(payload);
        trace("llm.gateway.fallback.", payload);
        emit("llm.gateway.fallback", payload);
    }

    public void publishFailure(String routeKey, LlmFailureClass failureClass, Throwable failure) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeKey", routeKey);
        payload.put("failureClass", failureClass == null ? LlmFailureClass.UNKNOWN.name() : failureClass.name());
        payload.put("exceptionClass", failure == null ? null : failure.getClass().getSimpleName());
        payload = safePayload(payload);
        trace("llm.gateway.failure.", payload);
        emit("llm.gateway.failure", payload);
    }

    private void emit(String type, Map<String, Object> payload) {
        try {
            SseEventPublisher publisher = ssePublisherProvider == null ? null : ssePublisherProvider.getIfAvailable();
            if (publisher != null) {
                publisher.emit(type, payload);
            }
        } catch (Exception ignore) {
            traceSuppressed("sse.emit");
        }
    }

    private static void trace(String prefix, Map<String, Object> payload) {
        try {
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                TraceStore.put(prefix + entry.getKey(), entry.getValue());
            }
        } catch (Exception ignore) {
            traceSuppressed("trace.write");
        }
    }

    private static Map<String, Object> safePayload(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            out.put(entry.getKey(), SafeRedactor.diagnosticValue(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    private static void traceSuppressed(String stage) {
        log.debug("[llm-gateway] suppressed stage={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
    }
}
