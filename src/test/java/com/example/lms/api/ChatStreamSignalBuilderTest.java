package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatStreamSignalBuilderTest {

    private static String fakeSupabaseSecret() {
        return "sb_" + "secret_" + "A".repeat(24);
    }

    @Test
    void traceSignalHashesIdentifiersAndKeepsStageCounts() {
        ChatStreamEvent.TraceSignal signal = ChatStreamSignalBuilder.buildTraceSignal(
                Map.of(
                        "orch.events.v1", List.of("a", "b"),
                        "rag.eval.stageCounts", Map.of("web", "3"),
                        "failureClass", "timeout",
                        "reasonCode", "rate-limit"),
                "trace-raw",
                "request-raw",
                "session-raw");

        assertEquals(2, signal.eventCount());
        assertEquals(3, signal.stageCounts().get("web"));
        assertEquals("timeout", signal.failureClass());
        assertEquals("rate-limit", signal.reasonCode());
        assertNotEquals("trace-raw", signal.traceIdHash());
        assertNotEquals("request-raw", signal.requestIdHash());
        assertNotEquals("session-raw", signal.sessionIdHash());
    }

    @Test
    void pipelineSnapshotDerivesFinalContextCountAndRedactsDisabledReason() {
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of(
                        "webCount", 2,
                        "vector.count", "4",
                        "disabledReason", "Authorization=secret-token",
                        "rag.route", "hybrid"),
                "rag",
                null,
                null);

        assertNotNull(snapshot);
        assertEquals(2, snapshot.webCount());
        assertEquals(4, snapshot.vectorCount());
        assertEquals(6, snapshot.finalContextCount());
        assertEquals("hybrid", snapshot.route());
        assertFalse(snapshot.disabledReason().contains("secret-token"));
    }

    @Test
    void traceSignalInfersCancellationFromProviderTaxonomyWhenFailureClassMissing() {
        ChatStreamEvent.TraceSignal signal = ChatStreamSignalBuilder.buildTraceSignal(
                Map.of(
                        "web.brave.cancelled", true,
                        "web.brave.exceptionType", "cancelled"),
                "trace-raw",
                "request-raw",
                "session-raw");

        assertEquals("cancelled", signal.failureClass());
    }

    @Test
    void pipelineSnapshotInfersCancellationFromProviderTaxonomyWhenFailureClassMissing() {
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of(
                        "web.serpapi.cancelled", true,
                        "web.serpapi.exceptionType", "cancelled",
                        "webCount", 1),
                "rag",
                null,
                null);

        assertNotNull(snapshot);
        assertEquals("cancelled", snapshot.failureClass());
    }

    @Test
    void pipelineSnapshotUsesTavilyCanonicalDisabledReason() {
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of(
                        "web.tavily.disabledReasonCanonical", "missing_tavily_api_key",
                        "webCount", 1),
                "rag",
                null,
                null);

        assertNotNull(snapshot);
        assertEquals("missing_tavily_api_key", snapshot.disabledReason());
    }

    @Test
    void transformerBlocksExposeOrderedRedactedRuntimeStatus() {
        ChatStreamEvent.TraceSignal traceSignal = ChatStreamSignalBuilder.buildTraceSignal(
                Map.of(
                        "rag.eval.stageCounts", Map.of("web", 2, "vector", 1),
                        "reasonCode", "ownerToken=private-token"),
                "trace-raw",
                "request-raw",
                "session-raw");
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of(
                        "plan.id", "safe.v1",
                        "rag.route", "hybrid",
                        "webCount", 2,
                        "vectorCount", 1,
                        "disabledReason", "api_key=secret-value"),
                "rag",
                8L,
                traceSignal);

        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.of("llm.model", "qwen3:8b"),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 10L, 44L, false),
                snapshot,
                traceSignal,
                null,
                null);

        assertEquals(6, blocks.size());
        assertEquals("intake", blocks.get(0).id());
        assertEquals("done", blocks.get(0).status());
        assertEquals("route", blocks.get(1).id());
        assertEquals("safe.v1", blocks.get(1).reason());
        assertEquals("retrieve", blocks.get(2).id());
        assertEquals("done", blocks.get(2).status());
        assertEquals("recover", blocks.get(5).id());
        assertEquals("warn", blocks.get(5).status());
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
        assertFalse(blocks.toString().contains("secret-value"), blocks.toString());
    }

    @Test
    void completedTransformerBlocksDoNotLeaveUnknownStagesQueued() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.of("llm.model", "qwen3:8b", "disabledReason", "ownerToken=private-token"),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 128L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("disabledReason", "api_key=secret-value"),
                        "chat",
                        17L,
                        null),
                null,
                null,
                null);

        assertEquals("done", blocks.get(0).status());
        assertEquals("route", blocks.get(1).id());
        assertEquals("skipped", blocks.get(1).status());
        assertEquals("skipped", blocks.get(2).status());
        assertEquals("skipped", blocks.get(3).status());
        assertEquals("done", blocks.get(4).status());
        assertEquals("warn", blocks.get(5).status());
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
        assertFalse(blocks.toString().contains("secret-value"), blocks.toString());
    }

    @Test
    void transformerBlocksExposeCoreDebugLanesFromTraceMetadata() {
        ChatStreamEvent.TraceSignal traceSignal = ChatStreamSignalBuilder.buildTraceSignal(
                Map.of(
                        "rag.eval.stageCounts", Map.of("web", 2, "dpp", 1),
                        "reasonCode", "Authorization=private-token"),
                "trace-raw",
                "request-raw",
                "session-raw");
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of(
                        "plan.id", "plan-dsl.safe",
                        "rag.route", "hybrid",
                        "webCount", 2,
                        "vectorCount", 1,
                        "finalContextCount", 3),
                "rag",
                9L,
                traceSignal);

        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("llm.model", "gemma4:26b"),
                        Map.entry("overdrive.stagesApplied", 1),
                        Map.entry("overdrive.finalCandidateCount", 5),
                        Map.entry("rag.anchor.reason", "anchor_seed"),
                        Map.entry("dpp.rerank.outputCount", 4),
                        Map.entry("hypernova.dppApplied", true),
                        Map.entry("cfvm.failureRecorder", "recorded"),
                        Map.entry("cfvm.boltzmannTemp", 0.7d),
                        Map.entry("supabase.evidenceNeeded", "project_ref_missing"),
                        Map.entry("supabase.service_role", fakeSupabaseSecret())),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 144L, false),
                snapshot,
                traceSignal,
                null,
                null);

        assertEquals(List.of("intake", "plan", "anchor", "retrieve", "rerank", "compose", "model", "cfvm", "recover", "supabase"),
                blocks.stream().map(ChatStreamEvent.TransformerBlockSignal::id).toList());
        assertEquals("done", blocks.get(1).status());
        assertEquals("plan-dsl.safe", blocks.get(1).reason());
        assertEquals("done", blocks.get(2).status());
        assertEquals("anchor_seed", blocks.get(2).reason());
        assertEquals("done", blocks.get(4).status());
        assertEquals("selected:4", blocks.get(4).reason());
        assertEquals("warn", blocks.get(7).status());
        assertEquals("recorded", blocks.get(7).reason());
        assertEquals("warn", blocks.get(9).status());
        assertEquals("project_ref_missing", blocks.get(9).reason());
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
        assertFalse(blocks.toString().contains(fakeSupabaseSecret()), blocks.toString());
    }

    @Test
    void transformerBlocksExposeQueryRewriteSuperTokensWhenPresent() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("queryTransformer.subQueries.superTokens.enabled", true),
                        Map.entry("queryTransformer.subQueries.superTokens.branchCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.tokenCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.subModelCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.subModelAssignmentCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.branchTitleCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.branchTitleHashCount", 2),
                        Map.entry("queryTransformer.subQueries.superTokens.axisCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.axes",
                                List.of("ownerToken=private-axis")),
                        Map.entry("queryTransformer.subQueries.superTokens.branchTitleHashes",
                                List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb", "ownerToken=private-title-hash")),
                        Map.entry("queryTransformer.subQueries.refined.paddedCount", 2),
                        Map.entry("queryTransformer.subQueries.superTokens.titlePresent", true),
                        Map.entry("queryTransformer.subQueries.superTokens.titleHash12", "abc123safehash"),
                        Map.entry("queryTransformer.subQueries.superTokens.titleLength", 42),
                        Map.entry("queryTransformer.subQueries.superTokens.rawTitle", "ownerToken=private-title")),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 144L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "rag"),
                        "rag",
                        9L,
                        null),
                null,
                null,
                null);

        assertEquals(List.of("intake", "plan", "rewrite", "anchor", "retrieve", "rerank", "compose", "model", "cfvm", "recover", "supabase"),
                blocks.stream().map(ChatStreamEvent.TransformerBlockSignal::id).toList());
        ChatStreamEvent.TransformerBlockSignal rewrite = blocks.get(2);
        assertEquals("rewrite", rewrite.id());
        assertEquals("done", rewrite.status());
        assertEquals("models:3_asgn:3_titles:3_title-counts:2_supers:3_branches:3_axes:3_padded:2", rewrite.reason());
        assertFalse(blocks.toString().contains("private-title"), blocks.toString());
        assertFalse(blocks.toString().contains("ownerToken"), blocks.toString());
    }

    @Test
    void transformerBlocksExposeModelTimeoutFastBailAsUiDebugState() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("llm.fastBailTimeout", true),
                        Map.entry("llm.fastBailTimeout.timeoutHits", 2),
                        Map.entry("llm.error.code", "timeout"),
                        Map.entry("llm.error.message", "Authorization=private-token")),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 75_000L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null),
                null,
                null,
                null);

        assertEquals(List.of("intake", "plan", "anchor", "retrieve", "rerank", "compose", "model", "cfvm", "recover", "supabase"),
                blocks.stream().map(ChatStreamEvent.TransformerBlockSignal::id).toList());
        ChatStreamEvent.TransformerBlockSignal model = blocks.get(6);
        assertEquals("model", model.id());
        assertEquals("warn", model.status());
        assertEquals("timeout-fast-bail:2", model.reason());
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
    }

    @Test
    void transformerBlocksExposeDefaultModelWaitAsUiDebugState() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("llm.defaultModel.waitStatus", true),
                        Map.entry("llm.defaultModel.waitStatus.code", "waiting_for_default_model")),
                ChatStreamEvent.StatusSignal.of("llm", "waiting_for_default_model",
                        "waiting for default model response", 15_000L, 250L, false),
                null,
                null,
                null,
                null);

        assertEquals(List.of("intake", "plan", "anchor", "retrieve", "rerank", "compose", "model", "cfvm", "recover", "supabase"),
                blocks.stream().map(ChatStreamEvent.TransformerBlockSignal::id).toList());
        ChatStreamEvent.TransformerBlockSignal model = blocks.get(6);
        assertEquals("model", model.id());
        assertEquals("running", model.status());
        assertEquals("waiting_for_default_model", model.reason());
    }

    @Test
    void transformerBlocksFlagSlowDefaultModelCompletionAsWarn() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("llm.defaultModel.route", "local"),
                        Map.entry("llm.model", "gemma4:26b")),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 75_000L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null),
                null,
                null,
                null);

        ChatStreamEvent.TransformerBlockSignal model = blocks.get(6);
        assertEquals("model", model.id());
        assertEquals("warn", model.status());
        assertEquals("slow-model-ms:75000", model.reason());
    }

    @Test
    void failureTagsSurfaceFallbackEvidenceInResilienceBlock() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("failureTags", List.of(
                                "ANSWER_MODE:FALLBACK_EVIDENCE",
                                fakeSupabaseSecret())),
                        Map.entry("llm.defaultModel.route", "local"),
                        Map.entry("llm.model", "gemma4:26b")),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 200L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null),
                null,
                null,
                null);

        ChatStreamEvent.TransformerBlockSignal recover = blocks.get(8);
        assertEquals("recover", recover.id());
        assertEquals("warn", recover.status());
        assertEquals("ANSWER_MODE:FALLBACK_EVIDENCE", recover.reason());
        assertFalse(blocks.toString().contains(fakeSupabaseSecret()), blocks.toString());
    }

    @Test
    void copilotLlmHealthPressureSurfacesInModelAndResilienceBlocks() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("dbg.copilot.causes", List.of(Map.of(
                                "id", "llm_health_pressure",
                                "score", 0.82d,
                                "title", "LLM runtime health pressure exceeded threshold"))),
                        Map.entry("llm.gateway.route.healthFailurePressure", 0.82d),
                        Map.entry("llm.gateway.route.healthRoutingHint", "llm_route_degrade"),
                        Map.entry("llm.gateway.route.healthFailureCount", 4),
                        Map.entry("llm.client.blank", true),
                        Map.entry("llm.client.promptHash", "hash:prompt-safe"),
                        Map.entry("llm.client.rawPrompt", "Authorization=private-token should not surface")),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 240L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null),
                null,
                null,
                null);

        assertEquals(List.of("intake", "plan", "anchor", "retrieve", "rerank", "compose", "model", "cfvm", "recover", "supabase"),
                blocks.stream().map(ChatStreamEvent.TransformerBlockSignal::id).toList());
        ChatStreamEvent.TransformerBlockSignal model = blocks.get(6);
        assertEquals("model", model.id());
        assertEquals("warn", model.status());
        assertEquals("llm_route_degrade", model.reason());
        ChatStreamEvent.TransformerBlockSignal recover = blocks.get(8);
        assertEquals("recover", recover.id());
        assertEquals("warn", recover.status());
        assertEquals("llm_health_pressure", recover.reason());
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
        assertFalse(blocks.toString().contains("Authorization"), blocks.toString());
    }

    @Test
    void copilotCauseSurfacesInDebugFxWithoutRawPromptOrToken() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.ofEntries(
                        Map.entry("dbg.copilot.causes", List.of(Map.of(
                                "id", "llm_health_pressure",
                                "title", "private prompt ownerToken=raw should not surface"))),
                        Map.entry("llm.client.rawPrompt", "Authorization=private-token should not surface")),
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null));

        assertEquals("llm_health_pressure", signal.code());
        assertEquals("resilience", signal.effect());
        assertEquals("llm_health_pressure", signal.labels().get("debugCause"));
        assertFalse(signal.toString().contains("private-token"), signal.toString());
        assertFalse(signal.toString().contains("ownerToken=raw"), signal.toString());
    }

    @Test
    void localLlmOperatorActionSurfacesInDebugFxLabelsWithoutRawPayloads() throws Exception {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.ofEntries(
                        Map.entry("dbg.copilot.causes", List.of(Map.of(
                                "id", "llm_health_pressure",
                                "score", 1.0d,
                                "title", "LLM runtime health pressure"))),
                        Map.entry("llm.localSmoke.operatorAction.triggerReason", "threshold_exceeded"),
                        Map.entry("llm.localSmoke.operatorAction.failureClass", "model_blank"),
                        Map.entry("llm.localSmoke.operatorAction.nextAction", "prefer_native_ollama_route"),
                        Map.entry("llm.localSmoke.operatorAction.actionScore", 100),
                        Map.entry("llm.localSmoke.operatorAction.scoreDelta", 85),
                        Map.entry("llm.client.rawPrompt", "Authorization=private-token should not surface"),
                        Map.entry("llm.client.rawModel", "qwen3:8b-private-owner-token")),
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null));

        assertEquals("llm_health_pressure", signal.code());
        assertEquals("threshold_exceeded", signal.labels().get("localLlmTriggerReason"));
        assertEquals("model_blank", signal.labels().get("localLlmFailureClass"));
        assertEquals("prefer_native_ollama_route", signal.labels().get("localLlmNextAction"));
        assertEquals("100", signal.labels().get("localLlmActionScore"));
        assertEquals("85", signal.labels().get("localLlmScoreDelta"));

        ChatStreamEvent event = ChatStreamEvent.debugFx(signal);
        ServerSentEvent<ChatStreamEvent> sse = ServerSentEvent.<ChatStreamEvent>builder(event)
                .event(event.type())
                .build();
        String json = new ObjectMapper().writeValueAsString(sse.data());
        JsonNode root = new ObjectMapper().readTree(json);
        JsonNode labels = root.path("debugFxSignal").path("labels");
        assertEquals("debug_fx", sse.event());
        assertEquals("debug_fx", root.path("type").asText());
        assertEquals("threshold_exceeded", labels.path("localLlmTriggerReason").asText());
        assertEquals("model_blank", labels.path("localLlmFailureClass").asText());
        assertEquals("prefer_native_ollama_route", labels.path("localLlmNextAction").asText());
        assertFalse(signal.toString().contains("private-token"), signal.toString());
        assertFalse(signal.toString().contains("Authorization"), signal.toString());
        assertFalse(signal.toString().contains("qwen3:8b"), signal.toString());
        assertFalse(json.contains("private-token"), json);
        assertFalse(json.contains("Authorization"), json);
        assertFalse(json.contains("qwen3:8b"), json);
        assertFalse(json.contains("rawPrompt"), json);
        assertFalse(json.contains("rawModel"), json);
    }

    @Test
    void numericSignalParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/api/ChatStreamSignalBuilder.java"));

        assertParserCatchNarrowed(source, "private static Double asDouble(Object value, Double fallback)");
        assertParserCatchNarrowed(source, "private static Long asLong(Object value)");
        assertTrue(source.contains("traceSuppressed(\"signal.asDouble\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"signal.asLong\", ignore);"));
        assertTrue(source.contains("private static String errorType(RuntimeException failure)"));
        assertTrue(source.contains("failure instanceof NumberFormatException"));
        assertTrue(source.contains("return \"invalid_number\";"));
        assertTrue(source.contains("String safeErrorType = errorType(failure);"));
        assertTrue(source.contains("TraceStore.put(\"chat.stream.signal.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"chat.stream.signal.suppressed.errorType\", safeErrorType);"));
        assertTrue(source.contains("TraceStore.put(\"chat.stream.signal.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("TraceStore.put(\"chat.stream.signal.suppressed.\" + safeStage + \".errorType\", safeErrorType);"));
        assertFalse(source.contains("failure == null ? \"unknown\" : failure.getClass().getSimpleName()"));
    }

    @Test
    void numericSignalFallbacksLeaveTraceBreadcrumbsWithoutRawValues() throws Exception {
        String raw = "Authorization=secret-not-a-number";
        Method asDouble = ChatStreamSignalBuilder.class.getDeclaredMethod("asDouble", Object.class, Double.class);
        Method asLong = ChatStreamSignalBuilder.class.getDeclaredMethod("asLong", Object.class);
        asDouble.setAccessible(true);
        asLong.setAccessible(true);

        TraceStore.clear();
        assertEquals(0.25d, asDouble.invoke(null, raw, 0.25d));
        assertEquals("signal.asDouble", TraceStore.get("chat.stream.signal.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("chat.stream.signal.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("chat.stream.signal.suppressed.signal.asDouble"));
        assertEquals("invalid_number", TraceStore.get("chat.stream.signal.suppressed.signal.asDouble.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));

        TraceStore.clear();
        assertNull(asLong.invoke(null, raw));
        assertEquals(Boolean.TRUE, TraceStore.get("chat.stream.signal.suppressed.signal.asLong"));
        assertEquals("invalid_number", TraceStore.get("chat.stream.signal.suppressed.signal.asLong.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));

        TraceStore.clear();
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertNotEquals(-1, start, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertNotEquals(-1, parse, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertNotEquals(-1, end, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertNotEquals(-1, method.indexOf("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertEquals(-1, method.indexOf("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertEquals(-1, method.indexOf("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }
}
