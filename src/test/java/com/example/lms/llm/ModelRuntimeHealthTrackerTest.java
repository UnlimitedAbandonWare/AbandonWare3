package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRuntimeHealthTrackerTest {

    @Test
    void seedLocalChatModelsCoverInstalledRoleDefaultsOnly() {
        assertTrue(ModelRuntimeHealthTracker.isSeedLocalChatModel("gemma4:26b"));
        assertTrue(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3:8b"));
        assertTrue(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3:30b"));
        assertTrue(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3-coder:30b"));
        assertTrue(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3-vl:8b"));

        assertFalse(ModelRuntimeHealthTracker.isSeedLocalChatModel("gemma4:31b-it-q4_K_M"));
        assertFalse(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3-embedding:4b"));
    }

    @Test
    void missingModelFailureBlocksPromotionUntilSuccess() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "gemma4:31b-it-q4_K_M",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "not_installed");

        assertFalse(tracker.isPromotable("local", "gemma4:31b-it-q4_K_M"));

        tracker.recordSuccess("local", "gemma4:31b-it-q4_K_M",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        assertTrue(tracker.isPromotable("local", "gemma4:31b-it-q4_K_M"));
    }

    @Test
    void upstream5xxBlocksSeedLocalPromotionUntilSuccess() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "gemma4:26b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "upstream_5xx");

        assertFalse(tracker.isPromotable("local", "gemma4:26b"));

        tracker.recordSuccess("local", "gemma4:26b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        assertTrue(tracker.isPromotable("local", "gemma4:26b"));
    }

    @Test
    void blankResponseBlocksSeedLocalPromotionUntilSuccess() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "blank_response");

        assertFalse(tracker.isPromotable("local", "qwen3:8b"));

        tracker.recordSuccess("local", "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        assertTrue(tracker.isPromotable("local", "qwen3:8b"));
    }

    @Test
    void publicSnapshotExposesBoundedFailurePressure() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "blank_response");
        tracker.recordFailure("local", "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "upstream_5xx");
        tracker.recordSuccess("local", "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        Map<String, Object> snapshot = tracker.redactedSnapshot("local", "qwen3:8b");

        assertEquals(3L, snapshot.get("sampleCount"));
        double failurePressure = ((Number) snapshot.get("failurePressure")).doubleValue();
        assertTrue(failurePressure > 0.0d && failurePressure < 1.0d);
        assertEquals("llm_route_degrade", snapshot.get("routingHint"));
    }

    @Test
    void embeddingModelsAreNeverPromotableForChat() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordSuccess("local", "qwen3-embedding:4b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        assertFalse(tracker.isPromotable("local", "qwen3-embedding:4b"));
    }

    @Test
    void failureReasonDoesNotExposeRawSecretsInPublicSnapshot() {
        String rawKey = "sk-" + "modelHealthSecretabcdefghijklmnopqrstuvwxyz";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();

        tracker.recordFailure("openai", "gpt-5.5-pro",
                OpenAiEndpointCompatibility.Endpoint.RESPONSES,
                "Authorization Bearer " + rawKey + " endpoint_mismatch");

        Map<String, Object> snapshot = tracker.redactedSnapshot("openai", "gpt-5.5-pro");
        String publicText = String.valueOf(snapshot);

        assertFalse(publicText.contains(rawKey));
        assertFalse(publicText.contains("Bearer " + rawKey));
    }

    @Test
    void publicSnapshotRedactsSecretShapedProviderAndModelLabels() {
        String rawKey = "sb_secret_" + "modelhealth123456";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();

        tracker.recordFailure("local-" + rawKey, "gemma4:26b-" + rawKey,
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS,
                "http_404");

        String publicText = String.valueOf(tracker.redactedSnapshots());

        assertFalse(publicText.contains(rawKey), publicText);
        assertFalse(publicText.contains("sb_secret_"), publicText);
    }

    @Test
    void freeFormEndpointMismatchReasonKeepsBlockingLabelWithoutRawText() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "gemma4:26b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS,
                "Responses endpoint mismatch for private prompt with owner token");

        Map<String, Object> snapshot = tracker.redactedSnapshot("local", "gemma4:26b");
        String lastReason = String.valueOf(snapshot.get("lastReason"));

        assertFalse(tracker.isPromotable("local", "gemma4:26b"));
        assertEquals("endpoint_mismatch", lastReason);
        assertFalse(lastReason.contains("private prompt"));
        assertFalse(lastReason.contains("owner token"));
    }

    @Test
    void reasonSanitizerUsesTraceLabelsInsteadOfSafeMessageText() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String redacted = SafeRedactor.safeMessage(reason, 120);"));
        assertTrue(source.contains("String label = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
    }
}
