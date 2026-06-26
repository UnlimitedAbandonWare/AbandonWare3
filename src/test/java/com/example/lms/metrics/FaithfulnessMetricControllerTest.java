package com.example.lms.metrics;

import com.example.lms.moe.NormalizedRagMetrics;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.AnswerQualityEvaluator;
import com.example.lms.service.soak.metrics.SoakMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaithfulnessMetricControllerTest {

    @BeforeEach
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        FaithfulnessMetricSnapshotStore.clear();
    }

    @Test
    void exposesReadOnlyFaithfulnessRoute() throws Exception {
        RequestMapping root = FaithfulnessMetricController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/metrics"}, root.value());
        assertArrayEquals(new String[]{"/faithfulness"},
                FaithfulnessMetricController.class.getMethod("faithfulness").getAnnotation(GetMapping.class).value());
    }

    @Test
    void returnsFaithfulnessTraceWithoutRawPromptOrSecrets() {
        TraceStore.put("rag.eval.normalized.retrievalHitRate", 0.75d);
        TraceStore.put("rag.eval.normalized.evidenceCoverage", 0.60d);
        TraceStore.put("rag.eval.normalized.sourceDiversity", 0.50d);
        TraceStore.put("rag.eval.normalized.resultDepth", 0.40d);
        TraceStore.put("rag.eval.normalized.balancedScore", 0.70d);
        TraceStore.put("rag.eval.normalized.latencyCost", 0.20d);
        TraceStore.put("rag.eval.normalized.fallbackCost", 0.10d);
        TraceStore.put("rag.eval.normalized.schemaVersion", "rag-neutral-v1");
        TraceStore.put("rag.answerQuality.faithfulnessScore", 0.42d);
        TraceStore.put("rag.answerQuality.decision", "REPAIR_WITH_WEB");
        TraceStore.put("rag.answerQuality.reason", "weak_relevance");
        TraceStore.put("rag.answerQuality.docCount", 2);
        TraceStore.put("rag.answerQuality.distinctSources", 1);
        TraceStore.put("rag.blackbox.compositeRisk", 0.31d);
        TraceStore.put("rag.blackbox.channel.evidenceGrounding", 0.58d);
        TraceStore.put("rag.blackbox.channel.hallucinationLikelihood", 0.22d);
        TraceStore.put("rag.blackbox.routingDecision", "DEGRADE");
        TraceStore.put("harmony.score.lastComputed", 88.0d);
        TraceStore.put("rawPrompt", "private ownerToken=secret");

        ResponseEntity<Map<String, Object>> response = new FaithfulnessMetricController().faithfulness();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("faithfulness-v1", response.getBody().get("schemaVersion"));
        assertEquals(0.75d, (Double) response.getBody().get("rag.retrievalHitRate"), 1e-9);
        assertEquals(0.60d, (Double) response.getBody().get("rag.evidenceCoverage"), 1e-9);
        assertEquals(0.70d, (Double) response.getBody().get("rag.balancedScore"), 1e-9);
        assertEquals(0.42d, (Double) response.getBody().get("quality.faithfulnessScore"), 1e-9);
        assertEquals("REPAIR_WITH_WEB", response.getBody().get("quality.decision"));
        assertEquals("weak_relevance", response.getBody().get("quality.reason"));
        assertEquals(2L, response.getBody().get("quality.docCount"));
        assertEquals(1L, response.getBody().get("quality.distinctSources"));
        assertEquals(0.31d, (Double) response.getBody().get("scorecard.compositeRisk"), 1e-9);
        assertEquals(0.58d, (Double) response.getBody().get("scorecard.evidenceGrounding"), 1e-9);
        assertEquals(0.22d, (Double) response.getBody().get("scorecard.hallucinationLikelihood"), 1e-9);
        assertEquals("DEGRADE", response.getBody().get("scorecard.routingDecision"));
        assertEquals(88.0d, (Double) response.getBody().get("harmony.score"), 1e-9);
        assertEquals("rag-neutral-v1", response.getBody().get("meta.traceSchemaVersion"));
        assertFalse(response.getBody().toString().contains("ownerToken"));
        assertFalse(response.getBody().toString().contains("private"));
    }

    @Test
    void returnsLastNormalizedMetricsAfterTraceContextClears() {
        NormalizedRagMetrics.fromRates(
                0.95d,
                0.90d,
                0.80d,
                0.50d,
                100.0d,
                0.10d).putTrace();
        TraceStore.clear();

        ResponseEntity<Map<String, Object>> response = new FaithfulnessMetricController().faithfulness();

        assertEquals(0.95d, (Double) response.getBody().get("rag.retrievalHitRate"), 1e-9);
        assertEquals(0.90d, (Double) response.getBody().get("rag.evidenceCoverage"), 1e-9);
        assertEquals(0.80d, (Double) response.getBody().get("rag.sourceDiversity"), 1e-9);
        assertEquals(0.50d, (Double) response.getBody().get("rag.resultDepth"), 1e-9);
        assertEquals(NormalizedRagMetrics.SCHEMA_VERSION, response.getBody().get("meta.traceSchemaVersion"));
    }

    @Test
    void returnsLastAnswerQualityMetricsAfterTraceContextClears() {
        AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator(null);
        evaluator.evaluateRetrieval("?", List.of(), 2, 0.5d, 2);
        TraceStore.clear();

        ResponseEntity<Map<String, Object>> response = new FaithfulnessMetricController().faithfulness();

        assertEquals(0.08d, (Double) response.getBody().get("quality.faithfulnessScore"), 1e-9);
        assertEquals("REWRITE_QUERY", response.getBody().get("quality.decision"));
        assertEquals("query_too_short", response.getBody().get("quality.reason"));
        assertEquals(0L, response.getBody().get("quality.docCount"));
        assertEquals(0L, response.getBody().get("quality.distinctSources"));
    }

    @Test
    void exposesLastSoakMetricsAfterTraceContextClears() {
        SoakMetricRegistry registry = new SoakMetricRegistry();
        registry.incFpFilterLegacyBypass();
        registry.recordWebCall(true);
        registry.recordWebMerge(4, 1);
        TraceStore.clear();

        ResponseEntity<Map<String, Object>> response = new FaithfulnessMetricController().faithfulness();

        assertEquals(1L, response.getBody().get("soak.fpFilterLegacyBypassCount"));
        assertEquals(1L, response.getBody().get("soak.webCalls"));
        assertEquals(1L, response.getBody().get("soak.webCallsWithNaver"));
        assertEquals(4L, response.getBody().get("soak.webMergedTotal"));
        assertEquals(1L, response.getBody().get("soak.webMergedFromNaver"));
        assertEquals(1.0d, (Double) response.getBody().get("soak.naverCallInclusionRate"), 1e-9);
        assertEquals(0.25d, (Double) response.getBody().get("soak.naverMergedShare"), 1e-9);
    }

    @Test
    void snapshotStoreKeepsOnlyAllowlistedRedactedScalars() {
        FaithfulnessMetricSnapshotStore.put("rawPrompt", "private ownerToken=secret");
        FaithfulnessMetricSnapshotStore.put("rag.answerQuality.reason", "private ownerToken=secret");
        FaithfulnessMetricSnapshotStore.put("soak.webCalls", 2L);
        FaithfulnessMetricSnapshotStore.put("rag.answerQuality.faithfulnessScore", 0.42d);
        FaithfulnessMetricSnapshotStore.put("rag.answerQuality.docs", Map.of("raw", "private"));

        String snapshot = FaithfulnessMetricSnapshotStore.snapshot().toString();

        assertNull(FaithfulnessMetricSnapshotStore.get("rawPrompt"));
        assertEquals(0.42d,
                (Double) FaithfulnessMetricSnapshotStore.get("rag.answerQuality.faithfulnessScore"), 1e-9);
        assertEquals(2L, FaithfulnessMetricSnapshotStore.get("soak.webCalls"));
        assertNull(FaithfulnessMetricSnapshotStore.get("rag.answerQuality.docs"));
        assertTrue(String.valueOf(FaithfulnessMetricSnapshotStore.get("rag.answerQuality.reason"))
                .startsWith("hash:"));
        assertFalse(snapshot.contains("ownerToken"));
        assertFalse(snapshot.contains("private"));
        assertFalse(snapshot.contains("secret"));
    }
}
