package com.example.lms.debug.ai;

import com.example.lms.api.DebugAiMetricsController;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugAiMetricsServicePostprocessTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void snapshotCallsPopulateBoundedHistoryNewestFirst() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.WEB_SEARCH, DebugEventLevel.INFO, Map.of("result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        for (int i = 0; i < 52; i++) {
            service.snapshot(10, 60_000);
        }

        List<DebugAiMetricSnapshot> history = service.snapshotHistory(100);

        assertEquals(48, history.size());
        for (int i = 1; i < history.size(); i++) {
            assertFalse(history.get(i - 1).generatedAt().isBefore(history.get(i).generatedAt()));
        }
    }

    @Test
    void compactSnapshotHonorsRequestedWindowMs() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now - 120_000, DebugProbeType.WEB_SEARCH, DebugEventLevel.INFO, Map.of("result", "old")));
        store.events.add(event(now - 1_000, DebugProbeType.WEB_SEARCH, DebugEventLevel.INFO, Map.of("result", "recent")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        Map<String, Object> oneMinute = service.compactSnapshot(10, 60_000);
        Map<String, Object> threeMinutes = service.compactSnapshot(10, 180_000);

        assertEquals(60_000L, oneMinute.get("windowMs"));
        assertEquals(1L, ((Number) oneMinute.get("totalEvents")).longValue());
        assertEquals(180_000L, threeMinutes.get("windowMs"));
        assertEquals(2L, ((Number) threeMinutes.get("totalEvents")).longValue());
    }

    @Test
    void breakerAndGenericProbesUseStableLayersAndSpringContextTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.NIGHTMARE_BREAKER, DebugEventLevel.WARN,
                Map.of("failureClass", "breaker_open")));
        store.events.add(event(now, DebugProbeType.GENERIC, DebugEventLevel.INFO,
                Map.of("failureClass", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);

        assertTrue(snapshot.layerCounts().containsKey("breaker"));
        assertTrue(snapshot.layerCounts().containsKey("spring.context"));
        DebugAiRawTile springContext = snapshot.tiles().stream()
                .filter(tile -> "SPRING_CONTEXT".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();
        assertEquals(2L, springContext.eventCount());
    }

    @Test
    void allOperationalProbeTypesUseStableLayerNames() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.HTTP, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.EXECUTOR, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.REACTOR, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.AUTOLEARN, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.CONTEXT_PROPAGATION, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.GUARD_CONTEXT, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.RULE_BREAK, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now, DebugProbeType.FAULT_MASK, DebugEventLevel.WARN, Map.of("failureClass", "masked")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        Map<String, Long> layers = service.snapshot(20, 60_000).layerCounts();

        assertTrue(layers.containsKey("http"));
        assertTrue(layers.containsKey("executor"));
        assertTrue(layers.containsKey("reactor"));
        assertTrue(layers.containsKey("learning.autolearn"));
        assertTrue(layers.containsKey("context.propagation"));
        assertTrue(layers.containsKey("guard.context"));
        assertTrue(layers.containsKey("guard.ruleBreak"));
        assertTrue(layers.containsKey("failsoft.faultMask"));
    }

    @Test
    void agentReportProbesStayOnAgentToolUsageTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.AGENT_REPORT_CFVM, DebugEventLevel.INFO, Map.of("result", "observed")));
        store.events.add(event(now + 1, DebugProbeType.AGENT_REPORT_TRACE, DebugEventLevel.INFO, Map.of("result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);
        DebugAiRawTile agentToolUsage = snapshot.tiles().stream()
                .filter(tile -> "AGENT_TOOL_USAGE".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(2L, agentToolUsage.eventCount());
        assertTrue(snapshot.layerCounts().containsKey("agent.report.cfvm"));
        assertTrue(snapshot.layerCounts().containsKey("agent.report.trace"));
    }

    @Test
    void scorecardIncludesHistoryBasedWarnAndErrorTrends() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.WEB_SEARCH, DebugEventLevel.WARN, Map.of("failureClass", "timeout")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        service.snapshot(10, 60_000);
        store.events.add(event(now + 1, DebugProbeType.WEB_SEARCH, DebugEventLevel.ERROR, Map.of("failureClass", "rate-limit")));

        Map<String, Object> scorecard = service.snapshot(10, 60_000).scorecard();

        assertEquals("flat", scorecard.get("warnTrend"));
        assertEquals("up", scorecard.get("errorTrend"));
        assertEquals(0L, ((Number) scorecard.get("warnDelta")).longValue());
        assertEquals(1L, ((Number) scorecard.get("errorDelta")).longValue());
    }

    @Test
    void scorecardTriggersHistoryBasedAnomalyForLlmErrorSpike() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.PROMPT, DebugEventLevel.INFO, Map.of("result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        service.snapshot(10, 60_000);

        store.events.add(event(now + 1, DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted")));
        store.events.add(event(now + 2, DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted")));
        store.events.add(event(now + 3, DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                Map.of("failureClass", "llm_upstream_retry_exhausted")));

        Map<String, Object> scorecard = service.snapshot(10, 60_000).scorecard();

        assertEquals(true, scorecard.get("anomalyTriggered"));
        assertEquals("error_delta_threshold", scorecard.get("anomalyReason"));
        assertEquals("LLM_MODEL_GUARD", scorecard.get("anomalyTile"));
        assertEquals("llm_upstream_retry_exhausted", scorecard.get("anomalyFailureClass"));
        assertTrue(((Number) scorecard.get("anomalyScore")).doubleValue() >= 0.7d);
        assertEquals(true, TraceStore.get("debug.ai.metrics.anomaly.triggered"));
        assertEquals("error_delta_threshold", TraceStore.get("debug.ai.metrics.anomaly.reason"));
    }

    @Test
    void tavilyProviderFailuresStayOnWebSearchTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.GENERIC, DebugEventLevel.WARN,
                Map.of("failureClass", "web.tavily.providerDisabled")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiRawTile webSearch = service.snapshot(10, 60_000).tiles().stream()
                .filter(tile -> "WEB_SEARCH".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, webSearch.eventCount());
        assertEquals(1L, webSearch.warnCount());
    }

    @Test
    void imageJobSignalsUseImageJobTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.GENERIC, DebugEventLevel.WARN,
                Map.of("layer", "image.job", "failureClass", "IMAGE_JOB_RESULT_WITHOUT_PUBLIC_URL")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);

        DebugAiRawTile imageJob = snapshot.tiles().stream()
                .filter(tile -> "IMAGE_JOB".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();
        DebugAiRawTile springContext = snapshot.tiles().stream()
                .filter(tile -> "SPRING_CONTEXT".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, imageJob.eventCount());
        assertEquals(1L, imageJob.warnCount());
        assertEquals(0L, springContext.eventCount());
    }

    @Test
    void queryRewriteSuperTokenEventsStayVisibleAsStructuredTransformerSignal() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.QUERY_TRANSFORMER, DebugEventLevel.INFO,
                Map.of(
                        "stage", "query_rewrite",
                        "superCount", 3,
                        "branchCount", 3,
                        "subModelCount", 3,
                        "branchTitleCount", 3,
                        "branchAxisCount", 3,
                        "paddedCount", 2,
                        "titlePresent", true,
                        "branchTitleHashes", List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb", "ownerToken=private-title-hash"),
                        "titleHash12", "abc123safehash")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);
        DebugAiRawTile queryTransformer = snapshot.tiles().stream()
                .filter(tile -> "QUERY_TRANSFORMER".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, queryTransformer.eventCount());
        assertEquals("query_rewrite.super_tokens", queryTransformer.topFailureClass());
        assertEquals(1L, snapshot.failureClassCounts().get("query_rewrite.super_tokens"));
        assertTrue(snapshot.layerCounts().containsKey("query.transformer"));
        assertEquals(3L, snapshot.scorecard().get("queryRewriteSubModelCount"));
        assertEquals(3L, snapshot.scorecard().get("queryRewriteBranchTitleCount"));
        assertEquals(2L, snapshot.scorecard().get("queryRewriteBranchTitleHashCount"));
        assertEquals(3L, snapshot.scorecard().get("queryRewriteBranchAxisCount"));
        assertEquals(2L, snapshot.scorecard().get("queryRewritePaddedCount"));
        assertFalse(String.valueOf(snapshot).contains("abc123safehash"));
    }

    @Test
    void queryRewriteIncompleteCoverageWarnsInStructuredTransformerTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.QUERY_TRANSFORMER, DebugEventLevel.WARN,
                Map.of(
                        "stage", "query_rewrite",
                        "failureClass", "query_rewrite.super_tokens.incomplete_coverage",
                        "superCount", 2,
                        "branchCount", 2,
                        "subModelCount", 2,
                        "branchAxisCount", 2,
                        "missingAxisCount", 1,
                        "outputCoverageComplete", false,
                        "titleHash12", "abc123safehash")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);
        DebugAiRawTile queryTransformer = snapshot.tiles().stream()
                .filter(tile -> "QUERY_TRANSFORMER".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, queryTransformer.eventCount());
        assertEquals(1L, queryTransformer.warnCount());
        assertEquals("query_rewrite.super_tokens.incomplete_coverage", queryTransformer.topFailureClass());
        assertEquals(1L, snapshot.failureClassCounts().get("query_rewrite.super_tokens.incomplete_coverage"));
        assertEquals(2L, snapshot.scorecard().get("queryRewriteSubModelCount"));
        assertEquals(2L, snapshot.scorecard().get("queryRewriteBranchAxisCount"));
        assertFalse(String.valueOf(snapshot).contains("abc123safehash"));
    }

    @Test
    void externalEvidenceEventsUseTheirOwnOpsConsoleTile() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        long now = System.currentTimeMillis();
        store.events.add(event(now, DebugProbeType.EXTERNAL_EVIDENCE, DebugEventLevel.WARN,
                Map.of(
                        "lanePolicy", "external_evidence",
                        "failureClass", "external_evidence_lane",
                        "laneCount", 4,
                        "readOnly", true,
                        "executionThread", false)));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000);
        DebugAiRawTile externalEvidence = snapshot.tiles().stream()
                .filter(tile -> "EXTERNAL_EVIDENCE".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();
        DebugAiRawTile springContext = snapshot.tiles().stream()
                .filter(tile -> "SPRING_CONTEXT".equals(tile.tileName()))
                .findFirst()
                .orElseThrow();

        assertEquals(1L, externalEvidence.eventCount());
        assertEquals(1L, externalEvidence.warnCount());
        assertEquals("external_evidence_lane", externalEvidence.topFailureClass());
        assertEquals(0L, springContext.eventCount());
        assertTrue(snapshot.layerCounts().containsKey("external.evidence"));
    }

    @Test
    void scheduledHistoryRecorderRecordsSnapshots() throws Exception {
        StaticDebugEventStore store = new StaticDebugEventStore();
        store.events.add(event(System.currentTimeMillis(), DebugProbeType.WEB_SEARCH, DebugEventLevel.INFO,
                Map.of("result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        DebugAiMetricsHistoryScheduler scheduler = new DebugAiMetricsHistoryScheduler(service);

        scheduler.recordHistorySnapshot();

        assertFalse(service.snapshotHistory(1).isEmpty());
        assertTrue(DebugAiMetricsHistoryScheduler.class
                .getDeclaredMethod("recordHistorySnapshot")
                .isAnnotationPresent(Scheduled.class));
    }

    @Test
    void controllerExposesCompactAndHistoryEndpoints() {
        StaticDebugEventStore store = new StaticDebugEventStore();
        store.events.add(event(System.currentTimeMillis(), DebugProbeType.PROMPT, DebugEventLevel.INFO,
                Map.of("result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);
        DebugAiMetricsController controller = new DebugAiMetricsController(service);

        Map<String, Object> compact = controller.compact(5, 300_000);
        controller.snapshot(5, 300_000);

        assertEquals(300_000L, compact.get("windowMs"));
        assertFalse(controller.history(5).isEmpty());
    }

    @Test
    void longValueParseFailureLeavesFixedStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/debug/ai/DebugAiMetricsService.java"));

        assertTrue(source.contains("traceSuppressed(\"debugAiMetrics.longValue\", ignore);"));
        assertTrue(source.contains(
                "TraceStore.put(\"debug.ai.metrics.suppressed.\" + safeStage, true);"));
    }

    @Test
    void invalidLongMetricUsesStableReasonCodeWithoutRawValue() {
        String rawMetric = "private latency ownerToken=fake-token";
        StaticDebugEventStore store = new StaticDebugEventStore();
        store.events.add(event(System.currentTimeMillis(), DebugProbeType.WEB_SEARCH, DebugEventLevel.INFO,
                Map.of("latencyMs", rawMetric, "result", "observed")));
        DebugAiMetricsService service = new DebugAiMetricsService(store);

        service.snapshot(5, 60_000);

        assertEquals("invalid_number",
                TraceStore.get("debug.ai.metrics.suppressed.debugAiMetrics.longValue.errorType"));
        assertEquals("debugAiMetrics.longValue", TraceStore.get("debug.ai.metrics.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("debug.ai.metrics.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawMetric));
    }

    private static DebugEvent event(long tsMs,
                                    DebugProbeType probe,
                                    DebugEventLevel level,
                                    Map<String, Object> data) {
        return new DebugEvent(
                "event-" + tsMs + "-" + probe,
                Instant.ofEpochMilli(tsMs),
                tsMs,
                level,
                probe,
                "fingerprint-" + probe + "-" + tsMs,
                "message",
                null,
                null,
                null,
                "test",
                "test",
                data,
                null,
                null);
    }

    private static final class StaticDebugEventStore extends DebugEventStore {
        private final List<DebugEvent> events = new ArrayList<>();

        @Override
        public List<DebugEvent> list(int limit) {
            int safeLimit = Math.max(1, Math.min(limit, events.size()));
            return List.copyOf(events.subList(0, safeLimit));
        }
    }
}
