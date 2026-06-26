package com.example.lms.telemetry;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class MlaBreadcrumb {
    private MlaBreadcrumb() {
    }

    public static void appendSseEvent(String eventType, Object sanitizedPayload) {
        Map<String, Object> row = baseRow("LoggingSseEventPublisher", "sse_telemetry_event", "sse_emit");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventType", SafeRedactor.traceLabelOrFallback(eventType, "event"));
        String payloadText = sanitizedPayload == null ? "" : String.valueOf(sanitizedPayload);
        data.put("payloadPresent", sanitizedPayload != null);
        putIfPresent(data, "payloadHash", SafeRedactor.hashValue(payloadText));
        data.put("payloadLength", payloadText.length());
        appendTraceFields(data, eventType, 0.0d, "sse_emit");
        row.put("data", data);

        TraceStore.append("ml.breadcrumbs.v1", row);
        TraceStore.put("mla.breadcrumb.step." + traceKeySegment(eventType), row);
    }

    public static void appendLlmReward(String armId, boolean success, long latencyMs, String failureClass) {
        String safeArm = SafeRedactor.traceLabelOrFallback(armId, "unknown");
        Map<String, Object> row = baseRow("LlmRouterBandit", "llm_router_reward", "llm_router_reward");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("llmArm", safeArm);
        data.put("reward", success ? 1.0d : 0.0d);
        data.put("outcome", success ? "success" : "fail");
        data.put("failureClass", SafeRedactor.traceLabelOrFallback(failureClass, "unknown").toLowerCase(Locale.ROOT));
        data.put("latencyMs", Math.max(0L, latencyMs));
        appendTraceFields(data, "llm_router_reward", success ? 1.0d : 0.0d, success ? "success" : "fail");
        row.put("data", data);

        TraceStore.append("ml.breadcrumbs.v1", row);
        TraceStore.put("mla.breadcrumb.llm.reward." + traceKeySegment(safeArm), row);
    }

    private static Map<String, Object> baseRow(String component, String rules, String decision) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("seq", TraceStore.nextSequence("ml.breadcrumbs.v1"));
        row.put("ts", Instant.now().toString());
        row.put("component", component);
        row.put("rules", rules);
        row.put("decision", decision);
        putIfPresent(row, "requestId", hashId(firstNonBlank(
                TraceStore.getString("requestId"),
                TraceStore.getString("rid"),
                TraceStore.getString("x-request-id"),
                TraceStore.getString("trace.id"))));
        putIfPresent(row, "sessionId", hashId(firstNonBlank(
                TraceStore.getString("sessionId"),
                TraceStore.getString("sid"),
                TraceStore.getString("conversation.sid"))));
        return row;
    }

    private static void appendTraceFields(Map<String, Object> data,
                                          String stage,
                                          double relevance,
                                          String routeDecision) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeRouteDecision = SafeRedactor.traceLabelOrFallback(routeDecision, "unknown");
        double safeRelevance = Double.isFinite(relevance) ? relevance : 0.0d;
        data.put("queryRedacted", true);
        data.put("stage", safeStage);
        data.put("relevance", safeRelevance);
        data.put("routeDecision", safeRouteDecision);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
        TraceStore.put("cihRag.breadcrumb.stage", safeStage);
        TraceStore.put("cihRag.breadcrumb.relevance", safeRelevance);
        TraceStore.put("cihRag.breadcrumb.routeDecision", safeRouteDecision);
        putIfPresent(data, "planId", SafeRedactor.traceLabel(TraceStore.get("plan.id")));
        putIfPresent(data, "jb", numeric(TraceStore.get("cfvm.jb.score")));
        putIfPresent(data, "cb", numeric(TraceStore.get("cfvm.cb.score")));
        putIfPresent(data, "plateId", SafeRedactor.traceLabel(TraceStore.get("artplate.selected")));
        putIfAbsent(data, "llmArm", SafeRedactor.traceLabel(TraceStore.get("llm.router.arm")));
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static void putIfAbsent(Map<String, Object> out, String key, Object value) {
        if (value != null && !out.containsKey(key)) {
            out.put(key, value);
        }
    }

    private static String traceKeySegment(String value) {
        String safe = SafeRedactor.traceLabelOrFallback(value, "unknown");
        if (safe.startsWith("hash:")) {
            safe = "hash_" + safe.substring("hash:".length());
        }
        return safe.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static String hashId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.startsWith("hash:") ? value : SafeRedactor.hashValue(value);
    }

    private static Double numeric(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (value == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException ignored) {
            TraceStore.put("mla.breadcrumb.suppressed.stage", "toDouble");
            TraceStore.put("mla.breadcrumb.suppressed.errorType", "invalid_number");
            TraceStore.put("mla.breadcrumb.suppressed.toDouble", true);
            TraceStore.put("mla.breadcrumb.suppressed.toDouble.errorType", "invalid_number");
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
