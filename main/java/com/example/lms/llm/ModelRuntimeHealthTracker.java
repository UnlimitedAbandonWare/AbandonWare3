package com.example.lms.llm;

import org.springframework.stereotype.Component;

import com.example.lms.trace.SafeRedactor;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ModelRuntimeHealthTracker {

    private static final Set<String> SEED_LOCAL_CHAT_MODELS = Set.of(
            ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL.toLowerCase(Locale.ROOT),
            ModelCapabilities.DEFAULT_LOCAL_FAST_MODEL.toLowerCase(Locale.ROOT),
            ModelCapabilities.DEFAULT_LOCAL_JUDGE_MODEL.toLowerCase(Locale.ROOT),
            ModelCapabilities.DEFAULT_LOCAL_CODER_MODEL.toLowerCase(Locale.ROOT),
            ModelCapabilities.DEFAULT_LOCAL_VISION_MODEL.toLowerCase(Locale.ROOT));

    private final ConcurrentHashMap<Key, Snapshot> snapshots = new ConcurrentHashMap<>();

    public static boolean isSeedLocalChatModel(String modelId) {
        String model = canonical(modelId);
        return model != null && SEED_LOCAL_CHAT_MODELS.contains(model);
    }

    public void recordSuccess(String provider, String modelId, OpenAiEndpointCompatibility.Endpoint endpoint) {
        String model = canonical(modelId);
        String displayModel = displayModel(modelId, model);
        String providerName = canonicalProvider(provider);
        if (model == null || providerName == null) {
            return;
        }
        OpenAiEndpointCompatibility.Endpoint ep = endpoint == null
                ? OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS
                : endpoint;
        snapshots.compute(new Key(providerName, model), (key, old) -> {
            long successes = old == null ? 1L : old.successCount + 1L;
            long failures = old == null ? 0L : old.failureCount;
            return new Snapshot(displayModel, providerName, ep, successes, failures, "ok", true);
        });
    }

    public void recordFailure(String provider, String modelId, OpenAiEndpointCompatibility.Endpoint endpoint, String reason) {
        String model = canonical(modelId);
        String displayModel = displayModel(modelId, model);
        String providerName = canonicalProvider(provider);
        if (model == null || providerName == null) {
            return;
        }
        OpenAiEndpointCompatibility.Endpoint ep = endpoint == null
                ? OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS
                : endpoint;
        String safeReason = sanitizeReason(reason);
        snapshots.compute(new Key(providerName, model), (key, old) -> {
            long successes = old == null ? 0L : old.successCount;
            long failures = old == null ? 1L : old.failureCount + 1L;
            return new Snapshot(displayModel, providerName, ep, successes, failures, safeReason, false);
        });
    }

    public boolean isPromotable(String provider, String modelId) {
        String model = canonical(modelId);
        String providerName = canonicalProvider(provider);
        if (model == null || providerName == null) {
            return false;
        }
        if ("local".equals(providerName)) {
            if (!ModelCapabilities.isLocalChatModelId(model)) {
                return false;
            }
            Snapshot snapshot = snapshots.get(new Key(providerName, model));
            if (snapshot != null && !snapshot.lastSuccess && isBlockingReason(snapshot.lastReason)) {
                return false;
            }
            return isSeedLocalChatModel(model) || (snapshot != null && snapshot.successCount > 0);
        }
        Snapshot snapshot = snapshots.get(new Key(providerName, model));
        return snapshot != null && snapshot.successCount > 0 && snapshot.lastSuccess;
    }

    public Optional<Snapshot> snapshot(String provider, String modelId) {
        String model = canonical(modelId);
        String providerName = canonicalProvider(provider);
        if (model == null || providerName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshots.get(new Key(providerName, model)));
    }

    public Map<String, Object> redactedSnapshot(String provider, String modelId) {
        Optional<Snapshot> snapshot = snapshot(provider, modelId);
        if (snapshot.isEmpty()) {
            return Map.of();
        }
        Snapshot s = snapshot.get();
        return toPublicMap(s);
    }

    public List<Map<String, Object>> redactedSnapshots() {
        List<Snapshot> values = new ArrayList<>(snapshots.values());
        values.sort(Comparator.comparing(Snapshot::provider, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Snapshot::model, String.CASE_INSENSITIVE_ORDER));
        return values.stream().map(ModelRuntimeHealthTracker::toPublicMap).toList();
    }

    private static Map<String, Object> toPublicMap(Snapshot s) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("model", publicLabel(s.model));
        out.put("provider", publicLabel(s.provider));
        out.put("endpoint", s.endpoint.name().toLowerCase(Locale.ROOT));
        out.put("successCount", s.successCount);
        out.put("failureCount", s.failureCount);
        out.put("sampleCount", sampleCount(s));
        out.put("failurePressure", failurePressure(s));
        out.put("routingHint", routingHint(s));
        out.put("lastReason", s.lastReason);
        out.put("lastSuccess", s.lastSuccess);
        return out;
    }

    private static long sampleCount(Snapshot s) {
        return Math.max(0L, s.successCount) + Math.max(0L, s.failureCount);
    }

    private static double failurePressure(Snapshot s) {
        long samples = sampleCount(s);
        if (samples <= 0L) {
            return 0.0d;
        }
        double pressure = Math.max(0L, s.failureCount) / (double) samples;
        if (!s.lastSuccess && isBlockingReason(s.lastReason)) {
            pressure = Math.max(pressure, 0.75d);
        }
        return Math.round(Math.min(1.0d, pressure) * 10000.0d) / 10000.0d;
    }

    private static String routingHint(Snapshot s) {
        double pressure = failurePressure(s);
        if (pressure >= 0.50d || (!s.lastSuccess && isBlockingReason(s.lastReason))) {
            return "llm_route_degrade";
        }
        if (pressure > 0.0d) {
            return "observe";
        }
        return "healthy";
    }

    private static String publicLabel(String value) {
        return SafeRedactor.traceLabelOrFallback(value, "unknown");
    }

    private static boolean isBlockingReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String r = reason.toLowerCase(Locale.ROOT);
        return r.contains("endpoint")
                || r.contains("mismatch")
                || r.contains("unsupported")
                || r.contains("not_installed")
                || r.contains("model_not_found")
                || r.contains("http_400")
                || r.contains("http_404")
                || r.contains("upstream_5xx")
                || r.contains("blank_response")
                || r.contains("http_5")
                || r.contains("service_unavailable")
                || r.contains("overloaded");
    }

    private static String sanitizeReason(String reason) {
        String known = knownBlockingReasonLabel(reason);
        if (known != null) {
            return known;
        }
        String label = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        String value = label.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            value = "unknown";
        }
        value = value.replaceAll("[^a-z0-9_.:-]", "_");
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static String knownBlockingReasonLabel(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String normalized = reason.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_");
        if (normalized.contains("endpoint") && normalized.contains("mismatch")) {
            return "endpoint_mismatch";
        }
        if (normalized.contains("unsupported")) {
            return "unsupported";
        }
        if (normalized.contains("not_installed")) {
            return "not_installed";
        }
        if (normalized.contains("model_not_found")) {
            return "model_not_found";
        }
        if (normalized.contains("http_400")) {
            return "http_400";
        }
        if (normalized.contains("http_404")) {
            return "http_404";
        }
        if (normalized.contains("blank_response")) {
            return "blank_response";
        }
        return null;
    }

    private static String canonicalProvider(String provider) {
        if (provider == null) {
            return null;
        }
        String p = provider.trim().toLowerCase(Locale.ROOT);
        return p.isBlank() ? null : p;
    }

    private static String canonical(String modelId) {
        String model = ModelCapabilities.canonicalModelName(modelId);
        if (model == null) {
            return null;
        }
        model = model.trim().toLowerCase(Locale.ROOT);
        return model.isBlank() ? null : model;
    }

    private static String displayModel(String modelId, String canonicalFallback) {
        String model = ModelCapabilities.canonicalModelName(modelId);
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        return canonicalFallback;
    }

    private record Key(String provider, String model) {
    }

    public record Snapshot(
            String model,
            String provider,
            OpenAiEndpointCompatibility.Endpoint endpoint,
            long successCount,
            long failureCount,
            String lastReason,
            boolean lastSuccess) {
    }
}
