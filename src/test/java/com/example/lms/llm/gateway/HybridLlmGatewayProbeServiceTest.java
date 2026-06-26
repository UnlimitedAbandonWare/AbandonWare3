package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.llm.spec.ModelSpecRegistry;
import com.example.lms.llm.spec.ModelSpecSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridLlmGatewayProbeServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void blocksWhenObservedSpecContextIsTooSmall() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setPath("build/tmp/test-model-spec-probe.json");
        ModelSpecRegistry registry = new ModelSpecRegistry(new ObjectMapper(), props);
        registry.publish(ModelSpecSnapshot.of("local", "small", "localhost", 1024, null, List.of("chat"), Map.of()));
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("small");
        cfg.setBaseUrl("http://localhost:11434/v1");
        cfg.setMinContextTokens(4096);

        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props, new ModelRuntimeHealthTracker(), registry, new LlmRouteScorer());

        RoutingEligibility eligibility = service.evaluate("small", cfg, "chat");

        assertFalse(eligibility.eligible());
        assertTrue(eligibility.failureClasses().contains(LlmFailureClass.CONTEXT_TOO_SMALL));
    }

    @Test
    void managedFileSearchIsNotEligibleOnLocalRoutes() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setEnabled(false);
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("gemma3:4b");
        cfg.setBaseUrl("http://localhost:11434/v1");
        cfg.setManagedFileSearch(true);

        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props, new ModelRuntimeHealthTracker(), new ModelSpecRegistry(new ObjectMapper(), props), new LlmRouteScorer());

        RoutingEligibility eligibility = service.evaluate("gemma", cfg, "chat");

        assertFalse(eligibility.eligible());
        assertTrue(eligibility.failureClasses().contains(LlmFailureClass.LOCAL_UNSUPPORTED_MANAGED_RAG));
    }

    @Test
    void exposesRuntimeHealthPressureMetadataWithoutBlockingRecoveredRoute() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setEnabled(false);
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("qwen3:8b");
        cfg.setBaseUrl("http://localhost:11434/v1");
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "qwen3:8b",
                com.example.lms.llm.OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "blank_response");
        tracker.recordFailure("local", "qwen3:8b",
                com.example.lms.llm.OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "upstream_5xx");
        tracker.recordSuccess("local", "qwen3:8b",
                com.example.lms.llm.OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props, tracker, new ModelSpecRegistry(new ObjectMapper(), props), new LlmRouteScorer());

        RoutingEligibility eligibility = service.evaluate("qwen-fast", cfg, "chat");

        assertTrue(eligibility.eligible());
        assertEquals(2L, eligibility.safeMeta().get("healthFailureCount"));
        assertEquals(3L, eligibility.safeMeta().get("healthSampleCount"));
        assertEquals("llm_route_degrade", eligibility.safeMeta().get("healthRoutingHint"));
        double pressure = ((Number) eligibility.safeMeta().get("healthFailurePressure")).doubleValue();
        assertTrue(pressure > 0.0d && pressure < 1.0d);
    }

    @Test
    void invalidEndpointHostLeavesRedactedTraceBreadcrumb() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setEnabled(false);
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("gemma3:4b");
        cfg.setBaseUrl("http://bad host/private-owner-token");

        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props, new ModelRuntimeHealthTracker(), new ModelSpecRegistry(new ObjectMapper(), props), new LlmRouteScorer());

        RoutingEligibility eligibility = service.evaluate("gemma", cfg, "chat");

        assertTrue(eligibility.eligible());
        assertTrue(eligibility.safeMeta().containsKey("endpointHost"));
        assertEquals(true, TraceStore.get("llm.gateway.probe.suppressed.endpointHost"));
        assertEquals("invalid_url",
                TraceStore.get("llm.gateway.probe.suppressed.endpointHost.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("private-owner-token"));
    }
}
