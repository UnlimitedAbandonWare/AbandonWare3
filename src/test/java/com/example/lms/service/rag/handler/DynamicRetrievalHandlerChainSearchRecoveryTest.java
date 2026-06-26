package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.service.rag.knowledge.UniversalLoreRegistry;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicRetrievalHandlerChainSearchRecoveryTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void zeroPrimaryResultsTriggerSearchRecoveryReason() {
        DynamicRetrievalHandlerChain chain = chain();

        String reason = ReflectionTestUtils.invokeMethod(
                chain,
                "searchFailureReason",
                null,
                List.of(),
                0,
                0,
                0,
                0);

        assertEquals("zero_result", reason);
    }

    @Test
    void afterFilterStarvationOverridesGenericZeroResult() {
        DynamicRetrievalHandlerChain chain = chain();
        TraceStore.put("web.naver.returnedCount", 3);
        TraceStore.put("web.naver.afterFilterCount", 0);

        String reason = ReflectionTestUtils.invokeMethod(
                chain,
                "searchFailureReason",
                null,
                List.of(),
                0,
                0,
                0,
                0);

        assertEquals("after_filter_starvation", reason);
    }

    @Test
    void tavilyTraceKeysTriggerSearchRecoveryReason() {
        DynamicRetrievalHandlerChain chain = chain();
        TraceStore.put("web.tavily.returnedCount", 3);
        TraceStore.put("web.tavily.afterFilterCount", 0);

        String starved = ReflectionTestUtils.invokeMethod(
                chain,
                "searchFailureReason",
                null,
                List.of(content("primary evidence", "doc-1")),
                1,
                0,
                0,
                0);

        assertEquals("after_filter_starvation", starved);

        TraceStore.clear();
        TraceStore.put("web.tavily.zeroResults", true);
        String zeroResult = ReflectionTestUtils.invokeMethod(
                chain,
                "searchFailureReason",
                null,
                List.of(content("primary evidence", "doc-1")),
                1,
                0,
                0,
                0);

        assertEquals("zero_result", zeroResult);
    }

    @Test
    void recoveryPayloadUsesRedactedQueryDiagnostics() {
        DynamicRetrievalHandlerChain chain = chain();
        @SuppressWarnings("unchecked")
        List<String> subqueries = ReflectionTestUtils.invokeMethod(
                chain,
                "recoverySubqueries",
                "raw ownerToken abc123 query",
                new Query("rewritten api_key should not leak"));

        assertTrue(subqueries.size() <= 4);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = ReflectionTestUtils.invokeMethod(
                chain,
                "recoveryEventPayload",
                "rag-recovery-1",
                "zero_result",
                "selfask_queryburst_parallel_web_vector_memory_history",
                List.of("web", "vector"),
                0,
                2,
                subqueries,
                2,
                "raw ownerToken abc123 query",
                "rewritten api_key should not leak");

        assertInstanceOf(Map.class, payload.get("query_original"));
        assertInstanceOf(Map.class, payload.get("query_rewritten"));
        assertFalse(String.valueOf(payload).contains("abc123"));
        assertFalse(String.valueOf(payload).contains("api_key should not leak"));
        assertEquals("zero_result", payload.get("zero_result_reason"));
        assertEquals(2, payload.get("recovered_count"));
    }

    @Test
    void recoveryBreadcrumbUsesRagPipelineContractAndRedactedPayload() {
        DynamicRetrievalHandlerChain chain = chain();
        TraceStore.put("rag.recovery.queryOriginalHash", "abc123hash");
        Map<String, Object> payload = Map.of(
                "zero_result_reason", "zero_result",
                "breadcrumb_id", "rag-recovery-1",
                "recovered_count", 2,
                "subquery_count", 1);

        ReflectionTestUtils.invokeMethod(chain, "emitRecoveryBreadcrumb", "done", payload, null);

        Map<?, ?> event = firstOrchEvent();
        assertEquals("rag.pipeline", event.get("kind"));
        assertEquals("recovery", event.get("phase"));
        assertEquals("search_failure", event.get("stage"));
        assertEquals("done", event.get("step"));
        assertEquals("ok", event.get("status"));
        assertEquals("DynamicRetrievalHandlerChain.runSearchFailureRecovery", event.get("component"));
        assertFalse(String.valueOf(event).contains("ownerToken"));

        Map<?, ?> input = assertInstanceOf(Map.class, event.get("input"));
        assertEquals("active_gated", input.get("mode"));
        assertEquals("abc123hash", input.get("queryHash"));
        Map<?, ?> failure = assertInstanceOf(Map.class, event.get("failure"));
        assertEquals("zero_result", failure.get("reasonCode"));
        Map<?, ?> control = assertInstanceOf(Map.class, event.get("control"));
        assertEquals("recovery", control.get("action"));
        assertEquals("rag-recovery-1", control.get("breadcrumbId"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(chain, "emitRecoveryBreadcrumb", "detect", payload,
                new IllegalStateException("raw ownerToken abc123"));

        Map<?, ?> errorEvent = firstOrchEvent();
        assertEquals("rag.pipeline", errorEvent.get("kind"));
        assertEquals("error", errorEvent.get("status"));
        assertFalse(String.valueOf(errorEvent).contains("raw ownerToken abc123"));
    }

    @Test
    void recoveryTimeoutCancelsWithoutInterruptingWorker() throws Exception {
        DynamicRetrievalHandlerChain chain = chain();
        ReflectionTestUtils.setField(chain, "searchFailureRecoveryTimeoutMs", 50L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CompletableFuture<?> future = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
            return null;
        }, executor);

        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            ReflectionTestUtils.invokeMethod(
                    chain,
                    "collectRecoveryBuckets",
                    List.of(future),
                    "rag-recovery-timeout",
                    "selfask_queryburst_parallel_web_vector_memory_history");

            assertTrue(future.isCancelled());
            assertEquals("no_interrupt", TraceStore.get("rag.recovery.cancelMode"));
            assertEquals("future_timeout", TraceStore.get("rag.recovery.timeout.reason"));
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fusionScorecardRecordsCountsAndKgRetentionWithoutContentLeakage() {
        DynamicRetrievalHandlerChain chain = chain();
        Content web = content("web public evidence", "web-1");
        Content kg = content("kg private evidence", "kg-1");

        ReflectionTestUtils.invokeMethod(
                chain,
                "traceFusionScorecard",
                List.of(web),
                List.of(),
                List.of(kg),
                List.of(),
                List.of(kg));

        Map<?, ?> scorecard = assertInstanceOf(Map.class, TraceStore.get("rag.fusion.scorecard"));
        assertEquals(1, scorecard.get("web"));
        assertEquals(0, scorecard.get("vector"));
        assertEquals(1, scorecard.get("kg"));
        assertEquals(0, scorecard.get("selfAsk"));
        assertEquals(2, scorecard.get("inputTotal"));
        assertEquals(1, scorecard.get("fusedCount"));
        assertEquals(0.5d, number(scorecard.get("kgInputShare")), 1e-9);
        assertEquals(1, scorecard.get("kgRetainedCount"));
        assertEquals(1.0d, number(scorecard.get("kgRetainedShare")), 1e-9);
        assertEquals(0, TraceStore.get("rag.fusion.sizes.selfask"));
        assertEquals(1, TraceStore.get("rag.fusion.sizes.web"));
        assertEquals(0, TraceStore.get("rag.fusion.sizes.vector"));
        assertEquals(1, TraceStore.get("rag.fusion.sizes.kg"));
        assertEquals(1.0d, number(TraceStore.get("rag.fusion.weights.kg")), 1e-9);
        assertEquals(1, TraceStore.get("rag.fusion.final.kgCount"));
        assertEquals(1, TraceStore.get("rag.fusion.final.totalCount"));
        assertFalse(String.valueOf(scorecard).contains("kg private evidence"));
        assertFalse(String.valueOf(scorecard).contains("web public evidence"));
    }

    @Test
    void fusionScorecardPreservesPerSourceScoreSignalsWithoutContentLeakage() {
        DynamicRetrievalHandlerChain chain = chain();
        Content web = content("web public evidence", "web-1", Map.of("score", 0.42d));
        Content vector = content("vector private evidence", "vector-1", Map.of("vector_score", 0.73d));
        Content kg = content("kg private evidence", "kg-1", Map.of("kg_score", 0.91d));

        ReflectionTestUtils.invokeMethod(
                chain,
                "traceFusionScorecard",
                List.of(web),
                List.of(vector),
                List.of(kg),
                List.of(),
                List.of(web, vector, kg));

        Map<?, ?> scorecard = assertInstanceOf(Map.class, TraceStore.get("rag.fusion.scorecard"));
        assertEquals(0.42d, number(scorecard.get("webScoreMean")), 1e-9);
        assertEquals(0.73d, number(scorecard.get("vectorScoreMean")), 1e-9);
        assertEquals(0.91d, number(scorecard.get("kgScoreMean")), 1e-9);
        assertEquals(0.42d, number(TraceStore.get("rag.fusion.score.mean.web")), 1e-9);
        assertEquals(0.73d, number(TraceStore.get("rag.fusion.score.mean.vector")), 1e-9);
        assertEquals(0.91d, number(TraceStore.get("rag.fusion.score.mean.kg")), 1e-9);
        assertFalse(String.valueOf(scorecard).contains("private evidence"));
    }

    @Test
    void fusionScoreTraceDropsNonFiniteScorecardValues() {
        Map<String, Object> scorecard = new java.util.LinkedHashMap<>();
        scorecard.put("webScoreMean", Double.POSITIVE_INFINITY);
        scorecard.put("vectorScoreMean", Double.NaN);
        scorecard.put("kgScoreMean", 0.91d);

        FusionScoreDiagnostics.traceScoreMeans(scorecard);

        assertEquals(0.0d, number(TraceStore.get("rag.fusion.score.mean.web")), 1e-9);
        assertEquals(0.0d, number(TraceStore.get("rag.fusion.score.mean.vector")), 1e-9);
        assertEquals(0.91d, number(TraceStore.get("rag.fusion.score.mean.kg")), 1e-9);
    }

    @Test
    void recoveryExecutorSourceUsesCancelShieldAndNoShutdownNow() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertTrue(source.contains("new ai.abandonware.nova.boot.exec.CancelShieldExecutorService"));
        assertTrue(source.contains("\"ragRecovery\""));
        assertTrue(source.contains("rag.recovery.executor.source"));
        assertFalse(source.contains("executor.shutdownNow()"));
    }

    @Test
    void dynamicRetrievalNumericParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertParserCatchNarrowed(source, "private static long traceLong(String key)");
        assertParserCatchNarrowed(source, "private static double traceDouble(String key)");
        assertParserCatchNarrowed(source, "private static double zero100LaneMultiplier(String lane)");
        assertParserCatchNarrowed(source, "private static int traceInt(String key, int defaultValue)");
        assertParserCatchNarrowed(source, "private static double traceDouble(String key, double defaultValue)");
        assertParserCatchNarrowed(source, "private static int metaInt(java.util.Map<String, Object> meta, String key, int def)");
        assertParserCatchNarrowed(source, "private static double metaDouble(java.util.Map<String, Object> meta, String key, double def)");
    }

    @Test
    void numericTraceAndMetadataHelpersDropNonFiniteNumbers() {
        TraceStore.put("chain.nonfinite", Double.POSITIVE_INFINITY);
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "traceTruthy", "chain.nonfinite"));
        assertEquals(0L, (Long) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "traceLong", "chain.nonfinite"));
        assertEquals(0.0d, (Double) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "traceDouble", "chain.nonfinite"), 0.0d);

        Map<String, Object> meta = Map.of(
                "enabled", Double.POSITIVE_INFINITY,
                "topK", Double.NaN,
                "score", Double.NEGATIVE_INFINITY,
                "stringScore", "Infinity");
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "metaBool", meta, "enabled", false));
        assertEquals(4, (Integer) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "metaInt", meta, "topK", 4));
        assertEquals(0.25d, (Double) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "metaDouble", meta, "score", 0.25d), 0.0d);
        assertEquals(0.25d, (Double) ReflectionTestUtils.invokeMethod(
                DynamicRetrievalHandlerChain.class, "metaDouble", meta, "stringScore", 0.25d), 0.0d);
    }

    @Test
    void retrievalStageClassifiesCancellationSeparatelyFromSilentFailure() {
        DynamicRetrievalHandlerChain chain = chain();

        ReflectionTestUtils.invokeMethod(
                chain,
                "recordRetrievalStage",
                "web",
                true,
                0,
                new java.util.concurrent.CancellationException("cancelled ownerToken abc123"),
                true);

        assertEquals("cancelled", TraceStore.get("retrieval.stage.web.failureClass"));
        assertEquals("CancellationException", TraceStore.get("retrieval.stage.web.exceptionType"));
        assertFalse(String.valueOf(TraceStore.context()).contains("ownerToken abc123"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(
                chain,
                "recordRetrievalStage",
                "web",
                true,
                0,
                new InterruptedException("interrupted ownerToken abc123"),
                true);

        assertEquals("cancelled", TraceStore.get("retrieval.stage.web.failureClass"));
        assertEquals("InterruptedException", TraceStore.get("retrieval.stage.web.exceptionType"));
        assertFalse(String.valueOf(TraceStore.context()).contains("ownerToken abc123"));
    }

    @Test
    void dynamicRetrievalHandlerLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()")
                        || line.contains(".toString()")
                        || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertTrue(rawThrowableLogLines.isEmpty(), rawThrowableLogLines.toString());
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
    }

    @Test
    void earlyFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("[Memory] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("NineTile alias correction failed, continuing with original query: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[OrderService] decide failed; using default: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[Memory] fail-soft errorHash={} errorLength={}"));
        assertTrue(source.contains("[Alias] NineTile alias correction failed, continuing with original query. errorHash={} errorLength={}"));
        assertTrue(source.contains("[OrderService] decide failed; using default. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
    }

    @Test
    void adaptiveWebAndSseFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("[AdaptiveWeb] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("SSE emit skipped: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[AdaptiveWeb] fail-soft errorHash={} errorLength={}"));
        assertTrue(source.contains("SSE emit skipped. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
    }

    @Test
    void kallocLoreSelfAskAndAnalyzeFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("[KAlloc] resource hints skip: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[KAlloc] skip: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[Lore] fast-match skip: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[Lore] Failed to inject lore: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[SelfAskGate] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[SelfAsk] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[AnalyzeGate] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("[Analyze] {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[KAlloc] resource hints skip errorHash={} errorLength={}"));
        assertTrue(source.contains("[KAlloc] skip errorHash={} errorLength={}"));
        assertTrue(source.contains("[Lore] fast-match skip errorHash={} errorLength={}"));
        assertTrue(source.contains("[Lore] Failed to inject lore errorHash={} errorLength={}"));
        assertTrue(source.contains("[SelfAskGate] fail-soft errorHash={} errorLength={}"));
        assertTrue(source.contains("[SelfAsk] fail-soft errorHash={} errorLength={}"));
        assertTrue(source.contains("[AnalyzeGate] fail-soft errorHash={} errorLength={}"));
        assertTrue(source.contains("[Analyze] fail-soft errorHash={} errorLength={}"));
    }

    @Test
    void recoveryReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("TraceStore.put(\"rag.recovery.reason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"rag.recovery.reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains("TraceStore.put(\"rag.recovery.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }

    @Test
    void thumbnailRecallDomainTraceUsesHashAndLength() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("TraceStore.put(\"uaw.thumb.recall.domain\""));
        assertTrue(source.contains("TraceStore.put(\"uaw.thumb.recall.domainHash\""));
        assertTrue(source.contains("TraceStore.put(\"uaw.thumb.recall.domainLength\""));
        assertTrue(source.contains("SafeRedactor.hashValue(recallDomain)"));
    }

    @Test
    void finalFusedOutputInvokesFinalSigmoidGateAndTracesDecision() {
        DynamicRetrievalHandlerChain chain = chain();
        ReflectionTestUtils.setField(chain, "finalSigmoidGate",
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"));
        List<Content> fused = List.of(
                content("Official evidence supports this answer.", "https://official.example/a"),
                content("Secondary evidence also supports it.", "https://official.example/b"));

        List<Content> out = new java.util.ArrayList<>(fused);
        chain.handle(new Query("safe query"), out);

        assertEquals(fused, out);
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.finalSigmoidGate.applied"));
        assertEquals("PASS", TraceStore.get("retrieval.finalSigmoidGate.result"));
    }

    @Test
    void finalSigmoidGateBlockTrimsAccumulatorAndTracesCount() {
        DynamicRetrievalHandlerChain chain = chain();
        ReflectionTestUtils.setField(chain, "finalSigmoidGate",
                new FixedGate(FinalSigmoidGate.GateResult.BLOCK));
        List<Content> accumulator = new java.util.ArrayList<>(List.of(
                content("evidence a", "https://unverified-a.example/doc"),
                content("evidence b", "https://unverified-b.example/doc"),
                content("evidence c", "https://unverified-c.example/doc"),
                content("evidence d", "https://unverified-d.example/doc"),
                content("evidence e", "https://unverified-e.example/doc"),
                content("evidence f", "https://unverified-f.example/doc")));

        chain.handle(new Query("unsafe sparse query"), accumulator);

        assertEquals("BLOCK", TraceStore.get("retrieval.finalSigmoidGate.result"));
        assertEquals(3, accumulator.size());
        assertEquals(3, TraceStore.get("retrieval.finalSigmoidGate.blockTrim.count"));
    }

    @Test
    void artPlateAliasFeedsKAllocTailSignals() {
        DynamicRetrievalHandlerChain chain = chain();
        ReflectionTestUtils.setField(chain, "kallocEnabled", true);
        ReflectionTestUtils.setField(chain, "kallocMaxTotalK", 24);
        ReflectionTestUtils.setField(chain, "kallocMinPerSource", 2);
        ReflectionTestUtils.setField(chain, "kallocKStep", 4);
        ReflectionTestUtils.setField(chain, "kallocMaxSourceShare", 0.65d);
        TraceStore.put("artplate.gate.moeStrategy.alias", "AP3_VEC_DENSE");
        java.util.Map<String, Object> md = new java.util.HashMap<>();

        com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.KPlan plan =
                ReflectionTestUtils.invokeMethod(chain, "__decideKPlan", "research", "vector recall", false, md);

        assertTrue(plan.vectorK > plan.webK);
        assertEquals("AP3_VEC_DENSE", TraceStore.get("retrieval.kalloc.artplate.alias"));
        assertEquals("AP3_VEC_DENSE", md.get("resource.artplate.alias"));
    }

    @Test
    void riskEmergentReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("putIfChanged(md, \"resource.riskEmergentReason\", risk.emergentAdjustment().reason())"));
        assertFalse(source.contains("TraceStore.put(\"resource.riskEmergentReason\", risk.emergentAdjustment().reason());"));
        assertFalse(source.contains("TraceStore.put(\"ml.risk.emergent.reason\", risk.emergentAdjustment().reason());"));
        assertFalse(source.contains("\"reason\", risk.emergentAdjustment().reason()"));
        assertFalse(source.contains(
                "SafeRedactor.safeMessage(risk.emergentAdjustment().reason(), 120)"));
        assertFalse(source.contains(
                "metadata.put(\"branch_quality_reason\", SafeRedactor.safeMessage(branchMetric.reason(), 120));"));
        assertTrue(source.contains(
                "SafeRedactor.traceLabelOrFallback(risk.emergentAdjustment().reason(), \"unknown\")"));
        assertTrue(source.contains("putIfChanged(md, \"resource.riskEmergentReason\", safeEmergentReason)"));
        assertTrue(source.contains("TraceStore.put(\"resource.riskEmergentReason\", safeEmergentReason);"));
        assertTrue(source.contains("TraceStore.put(\"ml.risk.emergent.reason\", safeEmergentReason);"));
        assertTrue(source.contains("\"reason\", safeEmergentReason"));
        assertTrue(source.contains("metadata.put(\"branch_quality_reason\","));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(branchMetric.reason(), \"unknown\")"));
    }

    @Test
    void loreFastMatchKeepsEntryWhenPrimaryEntityNameIsMissing() {
        UniversalLoreRegistry registry = new UniversalLoreRegistry() {
            @Override
            public List<DomainKnowledge> findLoreInText(String text) {
                return List.of(new DomainKnowledge(
                        "Genshin",
                        java.util.Arrays.asList(null, "makiba"),
                        "stable lore payload"));
            }
        };
        DynamicRetrievalHandlerChain chain = new DynamicRetrievalHandlerChain(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, registry, null);
        List<Content> accumulator = new java.util.ArrayList<>();

        chain.handle(new Query("makiba"), accumulator);

        assertEquals(1, accumulator.size());
        assertTrue(accumulator.get(0).textSegment().text().contains("stable lore payload"));
        assertEquals("makiba", accumulator.get(0).textSegment().metadata().getString("entity"));
    }

    private static Map<?, ?> firstOrchEvent() {
        Object eventsObj = TraceStore.get("orch.events.v1");
        assertTrue(eventsObj instanceof List<?>);
        return assertInstanceOf(Map.class, ((List<?>) eventsObj).get(0));
    }

    private static Content content(String text, String id) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of("doc_id", id, "source", id))));
    }

    private static Content content(String text, String id, Map<String, Object> metadata) {
        java.util.Map<String, Object> all = new java.util.LinkedHashMap<>();
        all.put("doc_id", id);
        all.put("source", id);
        all.putAll(metadata);
        return Content.from(TextSegment.from(text, Metadata.from(all)));
    }

    private static double number(Object value) {
        assertTrue(value instanceof Number);
        return ((Number) value).doubleValue();
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }

    private static DynamicRetrievalHandlerChain chain() {
        DynamicRetrievalHandlerChain chain = new DynamicRetrievalHandlerChain(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
        ReflectionTestUtils.setField(chain, "searchFailureRecoveryMaxSubqueries", 4);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateCitationMin", 3);
        return chain;
    }

    private static final class FixedGate extends FinalSigmoidGate {
        private final GateResult result;

        private FixedGate(GateResult result) {
            super(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "hard");
            this.result = result;
        }

        @Override
        public GateResult check(double compositeScore, double policyRisk, boolean hasStrongEvidence) {
            return result;
        }
    }
}
