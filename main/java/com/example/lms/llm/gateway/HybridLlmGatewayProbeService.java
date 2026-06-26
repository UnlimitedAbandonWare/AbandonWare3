package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.llm.spec.ModelSpecRegistry;
import com.example.lms.llm.spec.ModelSpecSnapshot;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class HybridLlmGatewayProbeService {

    private final LlmGatewayProperties properties;
    private final ModelRuntimeHealthTracker healthTracker;
    private final ModelSpecRegistry specRegistry;
    private final LlmRouteScorer scorer;

    public HybridLlmGatewayProbeService(
            LlmGatewayProperties properties,
            ModelRuntimeHealthTracker healthTracker,
            ModelSpecRegistry specRegistry,
            LlmRouteScorer scorer) {
        this.properties = properties;
        this.healthTracker = healthTracker;
        this.specRegistry = specRegistry;
        this.scorer = scorer;
    }

    public RoutingEligibility evaluate(String routeKey, LlmRouterProperties.ModelConfig cfg, String stage) {
        String provider = provider(routeKey, cfg);
        String model = cfg == null ? null : cfg.getName();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("enforcement", properties == null ? "observe" : properties.getEnforcement().name().toLowerCase(Locale.ROOT));
        meta.put("probeEnabled", properties == null || properties.getProbe().isEnabled());
        meta.put("endpointHost", endpointHost(cfg == null ? null : cfg.getBaseUrl()));

        if (properties == null || !properties.isEnabled() || cfg == null) {
            return RoutingEligibility.eligible(routeKey, provider, model, stage, 100, false, meta);
        }

        List<LlmFailureClass> failures = new ArrayList<>();
        if (!cfg.isEnabled()) {
            failures.add(LlmFailureClass.DISABLED);
        }
        if (!StringUtils.hasText(cfg.getName()) || !StringUtils.hasText(cfg.getBaseUrl())) {
            failures.add(LlmFailureClass.DISABLED);
            meta.put("disabledReason", "missing_route_config");
        }
        if (cfg.isManagedFileSearch() && isLocalProvider(provider)) {
            failures.add(LlmFailureClass.LOCAL_UNSUPPORTED_MANAGED_RAG);
        }

        Optional<ModelSpecSnapshot> spec = specRegistry == null
                ? Optional.empty()
                : specRegistry.snapshot(provider, model);
        if (spec.isPresent()) {
            ModelSpecSnapshot snapshot = spec.get();
            meta.put("specObserved", true);
            if (snapshot.contextTokens() != null) {
                meta.put("contextTokens", snapshot.contextTokens());
            }
            if (snapshot.embeddingDim() != null) {
                meta.put("embeddingDim", snapshot.embeddingDim());
            }
            if (cfg.getMinContextTokens() != null && snapshot.contextTokens() != null
                    && snapshot.contextTokens() < cfg.getMinContextTokens()) {
                failures.add(LlmFailureClass.CONTEXT_TOO_SMALL);
            }
            if (cfg.getEmbeddingDim() != null && snapshot.embeddingDim() != null
                    && !cfg.getEmbeddingDim().equals(snapshot.embeddingDim())) {
                failures.add(LlmFailureClass.EMBEDDING_DIM_MISMATCH);
            }
        } else {
            meta.put("specObserved", false);
        }

        if (healthTracker != null) {
            healthTracker.snapshot(provider, model).ifPresent(snapshot -> {
                meta.put("healthLastSuccess", snapshot.lastSuccess());
                meta.put("healthFailureCount", snapshot.failureCount());
                Map<String, Object> publicHealth = healthTracker.redactedSnapshot(provider, model);
                putIfPresent(meta, "healthSampleCount", publicHealth.get("sampleCount"));
                putIfPresent(meta, "healthFailurePressure", publicHealth.get("failurePressure"));
                putIfPresent(meta, "healthRoutingHint", publicHealth.get("routingHint"));
                if (!snapshot.lastSuccess()) {
                    failures.add(toFailure(snapshot.lastReason()));
                }
            });
        }

        int minScore = cfg.getMinRouteScore() == null ? properties.getMinRouteScore() : cfg.getMinRouteScore();
        int score = scorer.score(cfg, failures);
        boolean eligible = scorer.eligible(score, minScore, failures);
        meta.put("minRouteScore", minScore);

        return eligible
                ? RoutingEligibility.eligible(routeKey, provider, model, stage, score, cfg.isFallbackOnly(), meta)
                : RoutingEligibility.blocked(routeKey, provider, model, stage, score, cfg.isFallbackOnly(), failures, meta);
    }

    public boolean isEnforce() {
        return properties != null && properties.isEnforce();
    }

    public boolean cloudFallbackEnabled() {
        return properties != null && properties.getCloud().isEnabled();
    }

    public String cloudRouteKey() {
        return properties == null ? null : properties.getCloud().getRouteKey();
    }

    private static LlmFailureClass toFailure(String reason) {
        if (reason == null || reason.isBlank()) {
            return LlmFailureClass.UNKNOWN;
        }
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("model_not_found") || r.contains("http_404") || r.contains("not_found")) {
            return LlmFailureClass.MODEL_MISSING;
        }
        if (r.contains("auth") || r.contains("401") || r.contains("403")) {
            return LlmFailureClass.AUTH_MISSING;
        }
        if (r.contains("oom") || r.contains("vram")) {
            return LlmFailureClass.VRAM_OOM;
        }
        if (r.contains("timeout")) {
            return LlmFailureClass.TIMEOUT_SOFT;
        }
        return LlmFailureClass.HEALTH_DOWN;
    }

    private static String provider(String routeKey, LlmRouterProperties.ModelConfig cfg) {
        if (cfg != null && StringUtils.hasText(cfg.getProvider())) {
            return cfg.getProvider().trim().toLowerCase(Locale.ROOT);
        }
        String baseUrl = cfg == null ? null : cfg.getBaseUrl();
        if (baseUrl == null) {
            return "local";
        }
        String u = baseUrl.toLowerCase(Locale.ROOT);
        if (u.contains("api.openai.com")) {
            return "openai";
        }
        if (u.contains("api.groq.com")) {
            return "groq";
        }
        if (u.contains("api.cerebras.ai")) {
            return "cerebras";
        }
        if (u.contains("openrouter.ai")) {
            return "openrouter";
        }
        if (u.contains("opencode.ai")) {
            return "opencode";
        }
        if (routeKey != null && routeKey.toLowerCase(Locale.ROOT).contains("macmini")) {
            return "local";
        }
        return "local";
    }

    private static boolean isLocalProvider(String provider) {
        return provider == null || provider.isBlank() || "local".equalsIgnoreCase(provider) || "ollama".equalsIgnoreCase(provider);
    }

    private static void putIfPresent(Map<String, Object> meta, String key, Object value) {
        if (meta != null && value != null) {
            meta.put(key, value);
        }
    }

    private static String endpointHost(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(rawBaseUrl.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignore) {
            TraceStore.put("llm.gateway.probe.suppressed.endpointHost", true);
            TraceStore.put("llm.gateway.probe.suppressed.endpointHost.errorType", errorType(ignore));
            return null;
        }
    }

    private static String errorType(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }
}
