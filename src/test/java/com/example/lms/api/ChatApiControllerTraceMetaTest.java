package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatApiControllerTraceMetaTest {

    @Test
    void chatApiControllerDoesNotUseExactEmptyCatchBlocks() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "ChatApiController should leave fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void chatApiControllerScannerSamplesUseNamedSuppressionStages() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertTrue(source.contains("logSuppressed(\"cancel.cancelSession\");"));
        assertTrue(source.contains("logSuppressed(\"state.sessionLookup\");"));
        assertTrue(source.contains("logSuppressed(\"state.metaExtract\");"));
        assertTrue(source.contains("logSuppressed(\"chat.clientIp\");"));
        assertTrue(source.contains("logSuppressed(\"chat.attachments.autoInject\");"));
        assertTrue(source.contains("logSuppressed(\"stream.clientIp\");"));
        assertTrue(source.contains("logSuppressed(\"stream.status.started\");"));
        assertTrue(source.contains("logSuppressed(\"plan.preSearch.stream\");"));
        assertTrue(source.contains("logSuppressed(\"stream.runSink.forward\");"));
        assertTrue(source.contains("logSuppressed(\"stream.runSink.error\");"));
        assertTrue(source.contains("logSuppressed(\"stream.sessionBreadcrumb.mdc\");"));
        assertTrue(source.contains("logSuppressed(\"stream.sessionBreadcrumb.trace\");"));
        assertTrue(source.contains("logSuppressed(\"stream.sessionBreadcrumb.outer\");"));
        assertTrue(source.contains("logSuppressed(\"stream.sessionReady\");"));
        assertTrue(source.contains("logSuppressed(\"stream.chainRunner\");"));
        assertTrue(source.contains("logSuppressed(\"stream.traceHtml.prefetch\");"));
        assertTrue(source.contains("logSuppressed(\"stream.guardContext.webSupplier\");"));
        assertTrue(source.contains("logSuppressed(\"stream.answerMode\");"));
        assertTrue(source.contains("logSuppressed(\"stream.failureTags\");"));
        assertTrue(source.contains("logSuppressed(\"stream.searchTraceConsole\");"));
        assertTrue(source.contains("logSuppressed(\"stream.finalTraceMeta\");"));
        assertTrue(source.contains("logSuppressed(\"stream.finalTraceMeta.clear\");"));
        assertTrue(source.contains("logSuppressed(\"stream.answerModeTracePersist\");"));
        assertTrue(source.contains("logSuppressed(\"stream.status.complete\");"));
        assertTrue(source.contains("logSuppressed(\"stream.status.error\");"));
        assertTrue(source.contains("logSuppressed(\"parse.asLong\");"));
        assertTrue(source.contains("logSuppressed(\"parse.normalizeChatSessionId\");"));
        assertTrue(source.contains("logSuppressed(\"memory.rehydrate.traceSessionNotFound\");"));
        assertTrue(source.contains("logSuppressed(\"memory.rollingSummary.previousSnapshot\");"));
        assertTrue(source.contains("logSuppressed(\"memory.rollingSummary.update\");"));
        assertTrue(source.contains("logSuppressed(\"memory.rollingSummary.shadowVector\");"));
        assertTrue(source.contains("logSuppressed(\"memory.shadowVectorQueued.trace\");"));
        assertTrue(source.contains("logSuppressed(\"sync.sessionBreadcrumb.mdc\");"));
        assertTrue(source.contains("logSuppressed(\"sync.sessionBreadcrumb.trace\");"));
        assertTrue(source.contains("logSuppressed(\"sync.sessionBreadcrumb.outer\");"));
        assertTrue(source.contains("logSuppressed(\"sync.prefetchGuardContext\");"));
        assertTrue(source.contains("logSuppressed(\"sync.webSupplierGuardContext\");"));
        assertTrue(source.contains("logSuppressed(\"sync.answerMode\");"));
        assertTrue(source.contains("logSuppressed(\"sync.failureTags\");"));
        assertTrue(source.contains("logSuppressed(\"sync.searchTraceConsole\");"));
        assertTrue(source.contains("logSuppressed(\"sync.finalTraceMeta\");"));
        assertTrue(source.contains("logSuppressed(\"sync.finalTraceMeta.clear\");"));
        assertTrue(source.contains("logSuppressed(\"sync.traceHtml.final\");"));
        assertTrue(source.contains("logSuppressed(\"sync.answerModeTracePersist\");"));
        assertTrue(source.contains("logSuppressed(\"sync.attachmentMeta.extract\");"));
        assertTrue(source.contains("logSuppressed(\"sync.attachmentMeta\");"));
    }

    @Test
    void trace64DoesNotDecodeWhenTraceExposureIsOff() {
        String b64 = Base64.getEncoder().encodeToString("<b>trace</b>".getBytes(StandardCharsets.UTF_8));

        assertTrue(ChatApiController.restoreTraceMetaMessage(1L, "?TRACE64?" + b64, LocalDateTime.now(), false)
                .isEmpty());
    }

    @Test
    void traceSnapshotPointerRendersRedactedLinkOnlyWhenTraceExposureIsOn() {
        assertTrue(ChatApiController.restoreTraceMetaMessage(
                10L,
                "?TRACESNAP?snap_20260531-0821.01",
                LocalDateTime.now(),
                false).isEmpty());

        ChatApiController.MessageDto dto = ChatApiController.restoreTraceMetaMessage(
                        11L,
                        "?TRACESNAP?snap_20260531-0821.01",
                        LocalDateTime.of(2026, 5, 31, 8, 21),
                        true)
                .orElseThrow();

        assertEquals(11L, dto.turnId());
        assertEquals("system", dto.role());
        assertTrue(dto.content().contains("data-trace-snapshot-id=\"snap_20260531-0821.01\""));
        assertTrue(dto.content().contains("/api/diagnostics/trace/snapshots/snap_20260531-0821.01/html"));
        assertFalse(dto.content().contains("?TRACE64?"));
    }

    @Test
    void malformedTraceSnapshotPointersAreSkipped() {
        assertTrue(ChatApiController.restoreTraceMetaMessage(
                12L,
                "?TRACESNAP?../bad",
                LocalDateTime.now(),
                true).isEmpty());
    }

    @Test
    void invalidAndOversizedTrace64PayloadsAreSkipped() {
        assertTrue(ChatApiController.restoreTraceMetaMessage(1L, "?TRACE64?not base64!", LocalDateTime.now(), true)
                .isEmpty());
        assertTrue(ChatApiController.restoreTraceMetaMessage(1L, "?TRACE64?" + "a".repeat(64_001),
                LocalDateTime.now(), true).isEmpty());
    }

    @Test
    void exposedTrace64PayloadIsDecodedAndRedactedThroughMessageBoundary() {
        String b64 = Base64.getEncoder().encodeToString("<section>trace</section>".getBytes(StandardCharsets.UTF_8));

        ChatApiController.MessageDto dto = ChatApiController
                .restoreTraceMetaMessage(7L, "?TRACE64?" + b64, LocalDateTime.of(2026, 5, 30, 12, 0), true)
                .orElseThrow();

        assertEquals(7L, dto.turnId());
        assertEquals("system", dto.role());
        assertTrue(dto.content().contains("trace"));
    }

    @Test
    void legacyTraceHtmlPayloadsRestoreAsSummariesNotRawHtml() {
        String rawHtml = "<section>private restored trace html ownerToken=legacy-secret</section>";
        String b64 = Base64.getEncoder().encodeToString(rawHtml.getBytes(StandardCharsets.UTF_8));

        for (String content : List.of("?TRACE?" + rawHtml, "?TRACE64?" + b64)) {
            ChatApiController.MessageDto dto = ChatApiController
                    .restoreTraceMetaMessage(8L, content, LocalDateTime.of(2026, 5, 30, 12, 1), true)
                    .orElseThrow();

            assertEquals("system", dto.role());
            assertTrue(dto.content().contains("traceHtml"));
            assertTrue(dto.content().contains("hash12"));
            assertFalse(dto.content().contains(rawHtml));
            assertFalse(dto.content().contains("private restored trace html"));
            assertFalse(dto.content().contains("ownerToken"));
        }
    }

    @Test
    void productionChatApiDoesNotWriteNewTrace64Messages() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"))
                + Files.readString(Path.of("main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java"));

        assertFalse(source.contains("TRACE_META_PREFIX_B64 +"),
                "TRACE64 must remain legacy read-only compatibility, not a new writer");
        assertFalse(source.contains("String.format(\"%s%s\", TRACE_META_PREFIX_B64"),
                "TRACE64 must not be persisted through formatted system messages");
        assertFalse(source.contains("Base64.getEncoder().encodeToString"),
                "trace system messages must use snapshot ids, not new base64 payloads");
        assertTrue(source.contains("TRACE_SNAPSHOT_META_PREFIX + snapshotId"));
    }

    @Test
    void streamCompleteTransformerReusesFinalTraceMetadata() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int completeSignal = source.indexOf("ChatStreamEvent.StatusSignal completeSignal = ChatStreamEvent.StatusSignal.of(");
        int transformer = source.indexOf("ChatStreamSignalBuilder.buildTransformerBlocks(", completeSignal);
        int transformerEnd = source.indexOf("))));", transformer);

        assertTrue(completeSignal >= 0, "stream complete status emission should be locatable");
        assertTrue(transformer > completeSignal, "stream complete transformer emission should follow complete status");
        assertTrue(transformerEnd > transformer, "stream complete transformer call should be bounded");
        String call = source.substring(transformer, transformerEnd);

        assertTrue(source.contains("java.util.Map<String, Object> finalTransformerMeta = java.util.Map.of();"),
                "stream path should preserve the last redacted trace metadata for final UI status");
        assertTrue(call.contains("finalTransformerMeta,"),
                "complete transformer emission must not overwrite rich LLM/DPP/CFVM/Supabase debug state with empty metadata");
        assertFalse(call.contains("java.util.Map.of(),"),
                "empty metadata at completion can collapse rich transformer rail back to default blocks");
    }

    @Test
    void streamDebugFxPayloadCarriesLocalLlmOperatorActionLabelsWithoutRawPayloads() throws Exception {
        ChatStreamEvent event = ChatApiController.buildDebugFxEvent(
                java.util.Map.ofEntries(
                        java.util.Map.entry("dbg.copilot.causes", java.util.List.of(java.util.Map.of(
                                "id", "llm_health_pressure",
                                "score", 1.0d,
                                "title", "ownerToken=raw should not surface"))),
                        java.util.Map.entry("llm.localSmoke.operatorAction.triggerReason", "threshold_exceeded"),
                        java.util.Map.entry("llm.localSmoke.operatorAction.failureClass", "model_blank"),
                        java.util.Map.entry("llm.localSmoke.operatorAction.nextAction", "prefer_native_ollama_route"),
                        java.util.Map.entry("llm.localSmoke.operatorAction.actionScore", 100),
                        java.util.Map.entry("llm.localSmoke.operatorAction.scoreDelta", 85),
                        java.util.Map.entry("llm.client.rawPrompt", "Authorization=private-token should not surface"),
                        java.util.Map.entry("llm.client.rawModel", "qwen3:8b-private-owner-token")),
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        java.util.Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null));

        assertNotNull(event);
        assertEquals("debug_fx", event.type());
        assertNotNull(event.debugFxSignal());
        assertEquals("llm_health_pressure", event.debugFxSignal().code());
        assertEquals("threshold_exceeded", event.debugFxSignal().labels().get("localLlmTriggerReason"));
        assertEquals("model_blank", event.debugFxSignal().labels().get("localLlmFailureClass"));
        assertEquals("prefer_native_ollama_route", event.debugFxSignal().labels().get("localLlmNextAction"));
        assertEquals("100", event.debugFxSignal().labels().get("localLlmActionScore"));
        assertEquals("85", event.debugFxSignal().labels().get("localLlmScoreDelta"));

        String json = new ObjectMapper().writeValueAsString(event);
        assertTrue(json.contains("\"type\":\"debug_fx\""));
        assertTrue(json.contains("\"localLlmTriggerReason\":\"threshold_exceeded\""));
        assertTrue(json.contains("\"localLlmFailureClass\":\"model_blank\""));
        assertTrue(json.contains("\"localLlmNextAction\":\"prefer_native_ollama_route\""));
        assertFalse(json.contains("private-token"), json);
        assertFalse(json.contains("Authorization"), json);
        assertFalse(json.contains("qwen3:8b"), json);
        assertFalse(json.contains("ownerToken=raw"), json);
    }

    @Test
    void streamCompletionEmitsDebugFxFromFinalTraceMetadata() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int extraMeta = source.indexOf("java.util.Map<String, Object> extraMeta = TraceStore.getAll();");
        int debugFx = source.indexOf("ChatStreamEvent finalDebugFxEvent =", extraMeta);
        int debugFxCall = source.indexOf("buildDebugFxEvent(", debugFx);
        int emit = source.indexOf("sink.tryEmitNext(sse(finalDebugFxEvent));", debugFxCall);

        assertTrue(extraMeta >= 0, "stream completion must capture TraceStore metadata");
        assertTrue(debugFx > extraMeta, "debug_fx payload must be built after final TraceStore metadata is captured");
        assertTrue(debugFxCall > debugFx, "debug_fx payload must call the shared event builder");
        assertTrue(emit > debugFxCall, "debug_fx payload must be emitted as SSE after it is built");
        String window = source.substring(debugFxCall, Math.min(source.length(), emit + 80));
        assertTrue(window.contains("extraMeta,"));
        assertTrue(window.contains("finalTraceSignal,"));
        assertTrue(window.contains("finalPipelineSnapshot"));
        assertFalse(window.contains("java.util.Map.of(),"));
    }

    @Test
    void streamEmitsPreLlmDebugFxBeforeWaitingOnChatService() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int waitStatus = source.indexOf("emitDefaultModelWaitStatus(sink, __capturedBudget, __streamStartedNs);");
        int preLlm = source.indexOf("ChatStreamEvent preLlmDebugFxEvent =", waitStatus);
        int chatCall = source.indexOf("chatService.continueChat(dtoForCall, __webSupplier)", waitStatus);

        assertTrue(waitStatus >= 0, "LLM wait status emission should be locatable");
        assertTrue(preLlm > waitStatus, "pre-LLM debug_fx should be built after wait status is emitted");
        assertTrue(chatCall > preLlm, "pre-LLM debug_fx must be emitted before blocking chatService.continueChat");
        String window = source.substring(waitStatus, Math.min(source.length(), chatCall));
        assertTrue(window.contains("debugCopilotService.maybeEnrichTrace()"));
        assertTrue(window.contains("buildDebugFxEvent(preLlmMeta,"));
        assertTrue(window.contains("sink.tryEmitNext(sse(preLlmDebugFxEvent));"));
        assertTrue(window.contains("stream.preLlmDebugFx"));
        assertFalse(window.contains("java.util.Map.of(),"));
    }

    @Test
    void syncPromotesPreLlmDebugEventBeforeWaitingOnChatService() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int syncStart = source.indexOf("private ChatResponseDto handleChat(");
        int preLlm = source.indexOf("promoteDebugEvents(\"pre_llm\", preLlmMeta, \"ChatApiController.sync.preLlm\");",
                syncStart);
        int enrich = source.lastIndexOf("debugCopilotService.maybeEnrichTrace()", preLlm);
        int chatCall = source.indexOf("ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);",
                syncStart);

        assertTrue(syncStart >= 0, "sync handleChat should be locatable");
        assertTrue(preLlm > syncStart, "sync pre-LLM DebugEvent promotion should be locatable");
        assertTrue(enrich > syncStart && enrich < preLlm, "sync pre-LLM promotion should use enriched TraceStore metadata");
        assertTrue(chatCall > preLlm, "sync pre-LLM DebugEvent promotion must happen before chatService.continueChat");
        String window = source.substring(enrich, Math.min(source.length(), chatCall));
        assertTrue(window.contains("TraceStore.getAll()"));
        assertTrue(window.contains("debugCopilotService.maybeEnrichTrace()"));
        assertTrue(window.contains("logSuppressed(\"sync.preLlmDebugEvent\")"));
        assertFalse(window.contains("java.util.Map.of(),"));
    }

    @Test
    void streamDefaultModelWaitTransformerCarriesWaitMetadata() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int waitSignal = source.indexOf("ChatStreamEvent.StatusSignal waitSignal = ChatStreamEvent.StatusSignal.of(");
        int transformer = source.indexOf("ChatStreamSignalBuilder.buildTransformerBlocks(", waitSignal);
        int transformerEnd = source.indexOf("))));", transformer);

        assertTrue(waitSignal >= 0, "default model wait status emission should be locatable");
        assertTrue(transformer > waitSignal, "default model wait transformer emission should follow wait status");
        assertTrue(transformerEnd > transformer, "default model wait transformer call should be bounded");
        String call = source.substring(transformer, transformerEnd);

        assertTrue(source.contains("java.util.Map<String, Object> waitTransformerMeta = java.util.Map.of("),
                "default model wait path should expose a redacted UI metadata seam");
        assertTrue(source.contains("\"llm.defaultModel.waitStatus\""),
                "wait metadata should use an LLM-scoped diagnostic key");
        assertTrue(call.contains("waitTransformerMeta,"),
                "wait transformer emission must carry model wait metadata into the UI rail");
        assertFalse(call.contains("java.util.Map.of(),"),
                "empty metadata during model wait keeps the UI from showing why the model is pending");
    }

    @Test
    void syncChatResponseReturnsTraceTurnIdInDto() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertTrue(Pattern.compile("new\\s+ChatResponseDto\\(\\s*result\\.content\\(\\),\\s*session\\.getId\\(\\),\\s*modelUsedFinal,\\s*result\\.ragUsed\\(\\),\\s*answerModeFinal,\\s*traceTurnId,\\s*learningContextMeta,\\s*result\\.evidenceMetadata\\(\\),\\s*syncPipelineSnapshot\\s*\\)")
                        .matcher(source)
                        .find(),
                "sync /api/chat response must carry traceTurnId and pipelineSnapshot so frontend can open the current trace immediately");
    }

    @Test
    void streamDebugStatusAndThoughtMessagesDoNotExposeMojibakePlaceholders() throws IOException {
        String broken = "broken status * ... *&#47;";

        assertEquals("debug stream update", ChatStreamEvent.status(broken).data());
        assertEquals("debug stream update", ChatStreamEvent.thought(broken).data());
        assertEquals("Running web search", ChatStreamEvent.status("Running web search").data());
    }

    @Test
    void stateTraceHtmlUsesSummaryBoundary() throws IOException {
        String controller = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        String restorer = Files.readString(Path.of("main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java"));

        assertFalse(controller.contains("SafeRedactor.safeMessage(traceHtml, 12000)"));
        assertFalse(restorer.contains("SafeRedactor.safeMessage(html, 12000)"));
        assertTrue(controller.contains("SafeRedactor.diagnosticText(\"traceHtml\", traceHtml, 12000)"));
        assertTrue(restorer.contains("SafeRedactor.diagnosticText(\"traceHtml\", html, 12000)"));
    }

    @Test
    void chatApiLogsDoNotUseRawThrowableMessagesOrSessionIds() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"))
                + Files.readString(Path.of("main/java/com/example/lms/api/ChatRequestSettingsMerger.java"));
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("session {}: {}\", sessionId,"));
        assertFalse(source.contains("session {}: {}\", session.getId(),"));
        assertFalse(source.contains("sessionId={}): {}\", currentSessionId.get(),"));
        assertFalse(source.contains("Failed to authorize /cancel for session {}: {}\", resolvedSessionId"));
        assertFalse(source.contains("attachments from session {}\", ids.size(), sid"));
        assertFalse(source.contains("SSE stream detached by client (sessionId={}, resumePreserved=true)\", sid"));
        assertFalse(source.contains("tracePutIfAbsent(\"trace.id\", __capturedTrace);"));
        assertFalse(source.contains("TraceStore.putIfAbsent(\"http.path\", __httpPath);"));
        assertTrue(source.contains("tracePutIfAbsent(\"trace.id\", SafeRedactor.hashValue(__capturedTrace));"));
        assertTrue(source.contains("tracePutIfAbsent(\"http.path\", SafeRedactor.diagnosticValue(\"http.path\", __httpPath));"));
        assertTrue(source.contains("Failed to authorize /cancel for sessionHash={}: {}"));
        assertTrue(source.contains("sessionHash"));
    }

    @Test
    void samplingAdjustmentLogsDoNotWriteRawModelIdentifiers() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"))
                + Files.readString(Path.of("main/java/com/example/lms/api/ChatRequestSettingsMerger.java"));

        assertFalse(source.contains("for model={}\""));
        assertFalse(source.contains("sanitizedTemperature, effectiveModel);"));
        assertFalse(source.contains("sanitizedTopP, effectiveModel);"));
        assertFalse(source.contains("sanitizedFrequencyPenalty, effectiveModel);"));
        assertFalse(source.contains("sanitizedPresencePenalty, effectiveModel);"));
        assertTrue(source.contains("modelHash={} modelLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(effectiveModel)"));
    }

    @Test
    void numericRequestBodyParserOnlyCatchesNumberFormatException() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int start = source.indexOf("private static Long asLong");
        int parse = source.indexOf("Long.parseLong", start);
        int end = source.indexOf("\n    }", parse);
        assertTrue(start >= 0 && parse > start && end > parse, "asLong parser should be locatable");
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception");
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException");
    }

    @Test
    void asLongFallbackLeavesStableInvalidNumberBreadcrumb() throws Exception {
        TraceStore.clear();
        try {
            Method method = ChatApiController.class.getDeclaredMethod("asLong", Object.class);
            method.setAccessible(true);
            String raw = "ownerToken=raw-secret";

            Object parsed = method.invoke(null, raw);

            assertNull(parsed);
            assertEquals(Boolean.TRUE, TraceStore.get("chat.api.suppressed.parse.asLong"));
            assertEquals("invalid_number", TraceStore.get("chat.api.suppressed.parse.asLong.errorType"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(raw), trace);
            assertFalse(trace.contains("NumberFormatException"), trace);
        } finally {
            TraceStore.clear();
        }
    }
}
