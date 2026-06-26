package com.example.lms.llm.spec;

import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSpecRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void storesRedactedSnapshotsWithoutSecretMetadata() throws Exception {
        TraceStore.clear();
        LlmGatewayProperties props = new LlmGatewayProperties();
        Path path = tempDir.resolve("model-spec.json");
        props.getSpecRegistry().setPath(path.toString());
        ModelSpecRegistry registry = new ModelSpecRegistry(new ObjectMapper(), props);

        registry.publish(ModelSpecSnapshot.of(
                "ollama",
                "qwen3-embedding:4b",
                "localhost",
                8192,
                2560,
                List.of("embedding"),
                Map.of("apiKey", "secret-value", "source", "ollama_show")));

        assertTrue(registry.snapshot("ollama", "qwen3-embedding:4b").isPresent());
        assertEquals(2560, registry.snapshot("ollama", "qwen3-embedding:4b").orElseThrow().embeddingDim());
        assertEquals(SafeRedactor.hashValue("qwen3-embedding:4b"), TraceStore.get("llm.gateway.spec.modelHash"));
        assertEquals("qwen3-embedding:4b".length(), TraceStore.get("llm.gateway.spec.modelLength"));
        assertEquals(null, TraceStore.get("llm.gateway.spec.model"));
        String json = Files.readString(path);
        assertFalse(json.contains("secret-value"));
        assertTrue(json.contains("qwen3-embedding:4b"));
        TraceStore.clear();
    }

    @Test
    void persistFailureUsesStableTraceLabel() {
        TraceStore.clear();
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setPath("bad" + '\0' + "path");
        ModelSpecRegistry registry = new ModelSpecRegistry(new ObjectMapper(), props);

        registry.publish(ModelSpecSnapshot.of(
                "ollama",
                "qwen3-embedding:4b",
                "localhost",
                8192,
                2560,
                List.of("embedding"),
                Map.of("source", "ollama_show")));

        assertEquals("llm_gateway_spec_persist_failed", TraceStore.get("llm.gateway.spec.persistFailure"));
        assertFalse(String.valueOf(TraceStore.get("llm.gateway.spec.persistFailure"))
                .contains("InvalidPathException"));
        TraceStore.clear();
    }

    @Test
    void modelSpecRegistryDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/spec/ModelSpecRegistry.java"));

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "ModelSpecRegistry fail-soft paths need fixed-stage breadcrumbs instead of exact empty catches");
    }

    @Test
    void modelSpecTelemetryCatchesUseDirectSafeFallbackLogs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/spec/ModelSpecRegistry.java"));

        assertTrue(source.contains("Model spec registry telemetry skipped stage=publish_trace"));
        assertTrue(source.contains("Model spec registry telemetry skipped stage=persist_failure_trace"));
        assertTrue(source.contains("errorType=\" + errorType(traceError)"));
    }
}
