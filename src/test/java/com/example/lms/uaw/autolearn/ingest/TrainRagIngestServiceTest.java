package com.example.lms.uaw.autolearn.ingest;

import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.vector.VectorSidService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TrainRagIngestServiceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void ingestPreservesValidationMetadataForVectorQuarantine() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state.json").toString());
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("train_rag.jsonl");
        Files.writeString(jsonl, """
                {"question":"q","answer":"a","model":"gemma4:26b","sessionId":"s1","validation":{"questionType":"causal","selfAskLanes":["BQ","ER","RC"],"selfAskLaneCoverage":1.0,"sampleScore":0.82,"riskScore":0.7,"contradictionScore":0.42,"contradictionCause":"evidence_conflict","contaminationScore":0.22,"legacyContextScore":0.11,"requery":{"required":true,"confirmed":true},"rejectReasons":[],"thresholds":{"sampleScoreMin":0.55,"contaminationMax":0.30,"contextContaminationMax":0.35,"contradictionMax":0.60,"requeryPenalty":0.0,"mode":"dynamic"},"runtime":{"errorRateWindow":0.25},"anomalies":{"flags":[],"spike":false,"drift":false},"feedback":{"cfvmReward":0.76,"vectorDecision":"SHADOW_REVIEW"},"evaluationCriteria":["cause_effect_support","requery_confirmation_required"]}}
                """);

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(1, count);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), anyString(), metaCaptor.capture());
        verify(vectorStoreService).flush();
        Map<String, Object> meta = metaCaptor.getValue();
        assertEquals("causal", meta.get(VectorMetaKeys.META_LEARNING_QUESTION_TYPE));
        assertEquals("BQ,ER,RC", meta.get(VectorMetaKeys.META_LEARNING_SELF_ASK_LANES));
        assertEquals(1.0, ((Number) meta.get(VectorMetaKeys.META_LEARNING_SELF_ASK_LANE_COVERAGE)).doubleValue(), 0.0001);
        assertEquals(0.42, ((Number) meta.get(VectorMetaKeys.META_LEARNING_CONTRADICTION_SCORE)).doubleValue(), 0.0001);
        assertEquals("evidence_conflict", meta.get(VectorMetaKeys.META_LEARNING_CONTRADICTION_CAUSE));
        assertEquals(true, meta.get(VectorMetaKeys.META_LEARNING_REQUERY_REQUIRED));
        assertEquals(true, meta.get(VectorMetaKeys.META_LEARNING_REQUERY_CONFIRMED));
        assertEquals(0.22, ((Number) meta.get(VectorMetaKeys.META_LEARNING_CONTAMINATION_SCORE)).doubleValue(), 0.0001);
        assertEquals(0.11, ((Number) meta.get(VectorMetaKeys.META_LEARNING_LEGACY_CONTEXT_SCORE)).doubleValue(), 0.0001);
        assertEquals("cause_effect_support,requery_confirmation_required",
                meta.get(VectorMetaKeys.META_LEARNING_EVALUATION_CRITERIA));
        assertEquals(0.55, ((Number) meta.get(VectorMetaKeys.META_LEARNING_DYNAMIC_SAMPLE_THRESHOLD)).doubleValue(), 0.0001);
        assertEquals(0.30, ((Number) meta.get(VectorMetaKeys.META_LEARNING_DYNAMIC_CONTAMINATION_THRESHOLD)).doubleValue(), 0.0001);
        assertEquals(0.35, ((Number) meta.get(VectorMetaKeys.META_LEARNING_CONTEXT_CONTAMINATION_THRESHOLD)).doubleValue(), 0.0001);
        assertEquals(0.60, ((Number) meta.get(VectorMetaKeys.META_LEARNING_CONTRADICTION_THRESHOLD)).doubleValue(), 0.0001);
        assertEquals(0.25, ((Number) meta.get(VectorMetaKeys.META_LEARNING_ERROR_RATE_WINDOW)).doubleValue(), 0.0001);
        assertEquals(false, meta.get(VectorMetaKeys.META_LEARNING_ANOMALY_SPIKE));
        assertEquals(false, meta.get(VectorMetaKeys.META_LEARNING_ANOMALY_DRIFT));
        assertEquals(0.76, ((Number) meta.get(VectorMetaKeys.META_LEARNING_CFVM_REWARD)).doubleValue(), 0.0001);
        assertEquals("SHADOW_REVIEW", meta.get(VectorMetaKeys.META_LEARNING_VECTOR_DECISION));
        assertEquals("accepted", meta.get(VectorMetaKeys.META_LEARNING_VALIDATION_DECISION));
        assertEquals("SHADOW_REVIEW", meta.get(VectorMetaKeys.META_DELETE_DECISION));
        assertEquals("ACCEPTED", meta.get(VectorMetaKeys.META_AGENT_HANDOFF_DECISION));
        assertEquals("data/agent-handoff/codex/manifest.json", meta.get(VectorMetaKeys.META_AGENT_HANDOFF_MANIFEST));
        assertTrue(String.valueOf(meta.get(VectorMetaKeys.META_AGENT_HANDOFF_MANIFEST_HASH)).length() > 10);
        assertTrue(String.valueOf(meta.get(VectorMetaKeys.META_AGENT_HANDOFF_SAMPLE_HASH)).length() > 10);
        assertTrue(Double.parseDouble(String.valueOf(meta.get(VectorMetaKeys.META_CONTEXT_CONTAMINATION_SCORE))) > 0.0d);
    }

    @Test
    void ingestQuarantinesHighContradictionSamples() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_contradiction.json").toString());
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("train_rag_contradiction.jsonl");
        Files.writeString(jsonl, """
                {"question":"q","answer":"a","sessionId":"s1","validation":{"questionType":"causal","selfAskLanes":["BQ","ER","RC"],"selfAskLaneCoverage":1.0,"sampleScore":0.82,"riskScore":0.2,"contradictionScore":0.77,"contradictionCause":"evidence_conflict","contaminationScore":0.05,"legacyContextScore":0.0,"requery":{"required":true,"confirmed":true},"rejectReasons":[],"thresholds":{"sampleScoreMin":0.55,"contaminationMax":0.30,"contextContaminationMax":0.35,"requeryPenalty":0.0,"mode":"dynamic"},"runtime":{"errorRateWindow":0.10},"anomalies":{"flags":[],"spike":false,"drift":false},"feedback":{"cfvmReward":0.60,"vectorDecision":"SHADOW_REVIEW"}},"evaluationCriteria":["cause_effect_support"]}
                """);

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(1, count);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), anyString(), metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertEquals(0.77, ((Number) meta.get(VectorMetaKeys.META_LEARNING_CONTRADICTION_SCORE)).doubleValue(), 0.0001);
        assertEquals("evidence_conflict", meta.get(VectorMetaKeys.META_LEARNING_CONTRADICTION_CAUSE));
        assertEquals("QUARANTINE", meta.get(VectorMetaKeys.META_DELETE_DECISION));
        assertEquals("validation_contradiction_risk", meta.get(VectorMetaKeys.META_DELETE_REASON));
    }

    @Test
    void ingestDoesNotFailOpenWhenLegacyValidationHasNoRejectReasons() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_legacy_missing_reasons.json").toString());
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("train_rag_legacy_missing_reasons.jsonl");
        Files.writeString(jsonl, """
                {"question":"q","answer":"a","sessionId":"s1","validation":{"questionType":"causal","sampleScore":0.40,"riskScore":0.2,"contradictionScore":0.0,"contaminationScore":0.05,"legacyContextScore":0.0,"requery":{"required":false,"confirmed":false},"thresholds":{"sampleScoreMin":0.55,"contaminationMax":0.30,"contextContaminationMax":0.35,"contradictionMax":0.60,"requeryPenalty":0.0,"mode":"dynamic"},"runtime":{"errorRateWindow":0.10},"anomalies":{"flags":[],"spike":false,"drift":false},"feedback":{"cfvmReward":0.60,"vectorDecision":"SHADOW_REVIEW"}},"evaluationCriteria":["cause_effect_support"]}
                """);

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(1, count);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), anyString(), metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertEquals("rejected", meta.get(VectorMetaKeys.META_LEARNING_VALIDATION_DECISION));
        assertEquals("QUARANTINE", meta.get(VectorMetaKeys.META_DELETE_DECISION));
        assertEquals("validation_anomaly_or_threshold", meta.get(VectorMetaKeys.META_DELETE_REASON));
    }

    @Test
    void ingestQuarantinesAnomalousAcceptedSamples() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_anomaly.json").toString());
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("train_rag_anomaly.jsonl");
        Files.writeString(jsonl, """
                {"question":"q","answer":"a","sessionId":"s1","validation":{"questionType":"causal","selfAskLanes":["BQ","ER","RC"],"selfAskLaneCoverage":1.0,"sampleScore":0.82,"riskScore":0.7,"contaminationScore":0.22,"legacyContextScore":0.11,"requery":{"required":true,"confirmed":true},"rejectReasons":[],"thresholds":{"sampleScoreMin":0.55,"contaminationMax":0.30,"contextContaminationMax":0.35,"requeryPenalty":0.0,"mode":"dynamic"},"runtime":{"errorRateWindow":0.75},"anomalies":{"flags":["spike"],"spike":true,"drift":false},"feedback":{"cfvmReward":0.60,"vectorDecision":"QUARANTINE"}},"evaluationCriteria":["cause_effect_support"]}
                """);

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(1, count);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), anyString(), metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertEquals("spike", meta.get(VectorMetaKeys.META_LEARNING_ANOMALY_FLAGS));
        assertEquals(true, meta.get(VectorMetaKeys.META_LEARNING_ANOMALY_SPIKE));
        assertEquals(false, meta.get(VectorMetaKeys.META_LEARNING_ANOMALY_DRIFT));
        assertEquals("QUARANTINE", meta.get(VectorMetaKeys.META_LEARNING_VECTOR_DECISION));
        assertEquals("QUARANTINE", meta.get(VectorMetaKeys.META_DELETE_DECISION));
    }

    @Test
    void ingestQuarantinesAcceptedValidationWithAfterFilterZero() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_after_filter.json").toString());
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("train_rag_after_filter.jsonl");
        Files.writeString(jsonl, """
                {"question":"q","answer":"a","sessionId":"s1","afterFilterCount":0,"validation":{"accepted":true,"questionType":"causal","selfAskLanes":["BQ","ER","RC"],"selfAskLaneCoverage":1.0,"sampleScore":0.92,"riskScore":0.2,"contradictionScore":0.0,"contradictionCause":"unknown","contaminationScore":0.05,"legacyContextScore":0.0,"contextContaminationScore":0.05,"requery":{"required":true,"confirmed":true},"rejectReasons":[],"thresholds":{"sampleScoreMin":0.55,"contaminationMax":0.30,"contextContaminationMax":0.35,"contradictionMax":0.60,"requeryPenalty":0.0,"mode":"dynamic"},"runtime":{"evidenceCount":3,"afterFilterCount":0,"contextDiversity":0.85,"laneCoverage":1.0,"errorRateWindow":0.10},"anomalies":{"flags":[],"spike":false,"drift":false},"feedback":{"cfvmReward":0.60,"vectorDecision":"SHADOW_REVIEW"}},"evaluationCriteria":["cause_effect_support"]}
                """);

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(1, count);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), anyString(), metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertEquals(0, meta.get("afterFilterCount"));
        assertEquals("rejected", meta.get(VectorMetaKeys.META_LEARNING_VALIDATION_DECISION));
        assertEquals("QUARANTINE", meta.get(VectorMetaKeys.META_DELETE_DECISION));
        assertEquals("validation_after_filter_starvation", meta.get(VectorMetaKeys.META_DELETE_REASON));
    }

    @Test
    void ingestSkipsInvalidLinesAndContinuesToLaterValidRows() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_invalid.json").toString());
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("train_rag_invalid.jsonl");
        Files.writeString(jsonl, String.join("\n",
                "",
                "not-json",
                "{\"question\":\"skip\",\"sessionId\":\"s1\"}",
                "{\"question\":\"q\",\"answer\":\"a\",\"sessionId\":\"s1\",\"validation\":{\"rejectReasons\":[],\"thresholds\":{\"contaminationMax\":0.30,\"contextContaminationMax\":0.35},\"anomalies\":{\"flags\":[]},\"feedback\":{\"vectorDecision\":\"SHADOW_REVIEW\"}}}"));

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(1, count);
        verify(vectorStoreService, times(1)).enqueue(anyString(), anyString(), anyString(), any());
        verify(vectorStoreService).flush();
        assertEquals(1L, TraceStore.getLong("uaw.retrain.ingest.count"));
        assertEquals(2L, TraceStore.getLong("uaw.retrain.ingest.parsed"));
        assertEquals(1L, TraceStore.getLong("uaw.retrain.ingest.queued"));
        assertEquals(0L, TraceStore.getLong("uaw.retrain.ingest.failed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) TraceStore.get("uaw.retrain.ingest.summary");
        assertEquals(1, summary.get("skippedBlankLines"));
        assertEquals(2, summary.get("skippedInvalidLines"));
    }

    @Test
    void nonFiniteValidationNumbersDoNotReachVectorMetadata() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_nonfinite.json").toString());
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("train_rag_nonfinite.jsonl");
        Files.writeString(jsonl, """
                {"question":"q","answer":"a","sessionId":"s1","validation":{"accepted":true,"sampleScore":"NaN","riskScore":"Infinity","contradictionScore":"Infinity","contaminationScore":"Infinity","legacyContextScore":"NaN","rejectReasons":[],"thresholds":{"sampleScoreMin":"NaN","contaminationMax":"Infinity","contextContaminationMax":"NaN","contradictionMax":"Infinity","requeryPenalty":"NaN"},"anomalies":{"flags":[]},"feedback":{"cfvmReward":"Infinity","vectorDecision":"SHADOW_REVIEW"}}}
                """);

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        assertEquals(1, service.ingestNewSamples(jsonl, "ds", () -> false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), anyString(), metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertFalse(meta.values().stream()
                .map(String::valueOf)
                .anyMatch(v -> v.contains("NaN") || v.contains("Infinity")));
        assertEquals(0.0d, ((Number) meta.get(VectorMetaKeys.META_LEARNING_SAMPLE_SCORE)).doubleValue(), 0.0001d);
        assertEquals(0.50d, ((Number) meta.get(VectorMetaKeys.META_LEARNING_CONTAMINATION_SCORE)).doubleValue(), 0.0001d);
        assertEquals(0.35d,
                ((Number) meta.get(VectorMetaKeys.META_LEARNING_DYNAMIC_CONTAMINATION_THRESHOLD)).doubleValue(),
                0.0001d);
        assertEquals("QUARANTINE", meta.get(VectorMetaKeys.META_DELETE_DECISION));
    }

    @Test
    void ingestRecordsVectorUpsertFailureCounters() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_fail.json").toString());
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("train_rag_fail.jsonl");
        Files.writeString(jsonl, """
                {"question":"q","answer":"a","sessionId":"s1","validation":{"rejectReasons":[],"thresholds":{"contaminationMax":0.30,"contextContaminationMax":0.35},"anomalies":{"flags":[]},"feedback":{"vectorDecision":"SHADOW_REVIEW"}}}
                """);
        doThrow(new RuntimeException("boom")).when(vectorStoreService).flush();

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(0, count);
        verify(vectorStoreService).enqueue(anyString(), anyString(), anyString(), any());
        verify(vectorStoreService).flush();
        assertEquals(0L, TraceStore.getLong("uaw.retrain.ingest.count"));
        assertEquals(1L, TraceStore.getLong("uaw.retrain.ingest.parsed"));
        assertEquals(1L, TraceStore.getLong("uaw.retrain.ingest.queued"));
        assertEquals(1L, TraceStore.getLong("uaw.retrain.ingest.failed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) TraceStore.get("uaw.retrain.ingest.summary");
        assertEquals("vector_upsert_fail", summary.get("reason"));
    }

    @Test
    void defaultMetadataProjectionDoesNotEnqueueRawQuestionAnswerText() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_metadata_only.json").toString());
        props.getRetrain().setMaxIngestLinesPerRun(10);

        String supabaseSource = "sb_publishable_" + "metadata01";
        Path jsonl = tempDir.resolve("train_rag_metadata_only.jsonl");
        Files.writeString(jsonl, """
                {"question":"raw private question sk-test-secret","answer":"raw private answer %s%s","sessionId":"s1","source":"uaw_autolearn %s","validation":{"accepted":true,"sampleScore":0.91,"contaminationScore":0.01,"legacyContextScore":0.0,"rejectReasons":[],"thresholds":{"sampleScoreMin":0.55,"contaminationMax":0.30,"contextContaminationMax":0.35,"contradictionMax":0.60},"anomalies":{"flags":[]},"feedback":{"vectorDecision":"SHADOW_REVIEW"}}}
                """.formatted("Bearer ", "abc.def.ghi", supabaseSource));

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(1, count);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), textCaptor.capture(), metaCaptor.capture());
        String text = textCaptor.getValue();
        assertTrue(text.contains("UAW AutoLearn metadata projection"));
        assertTrue(text.contains("validationDecision=accepted"));
        assertFalse(text.contains("raw private question"));
        assertFalse(text.contains("raw private answer"));
        assertFalse(text.contains("Bearer " + "abc.def.ghi"));
        assertFalse(text.contains(supabaseSource));
        assertEquals("METADATA_ONLY", metaCaptor.getValue().get("vector_projection_mode"));
    }

    @Test
    void rawShadowProjectionKeepsLegacyRawContentOnlyWhenExplicitlyOptedIn() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_raw_shadow.json").toString());
        props.getRetrain().setVectorProjectionMode("RAW_SHADOW_QUARANTINE");
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("train_rag_raw_shadow.jsonl");
        Files.writeString(jsonl, """
                {"question":"legacy raw question","answer":"legacy raw answer","sessionId":"s1","validation":{"accepted":true,"sampleScore":0.91,"contaminationScore":0.01,"legacyContextScore":0.0,"rejectReasons":[],"thresholds":{"sampleScoreMin":0.55,"contaminationMax":0.30,"contextContaminationMax":0.35,"contradictionMax":0.60},"anomalies":{"flags":[]},"feedback":{"vectorDecision":"SHADOW_REVIEW"}}}
                """);

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(1, count);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), textCaptor.capture(), metaCaptor.capture());
        assertTrue(textCaptor.getValue().contains("legacy raw question"));
        assertTrue(textCaptor.getValue().contains("legacy raw answer"));
        assertEquals("RAW_SHADOW_QUARANTINE", metaCaptor.getValue().get("vector_projection_mode"));
    }

    @Test
    void rawShadowProjectionRedactsSecretsBeforeVectorEnqueue() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_raw_shadow_redacted.json").toString());
        props.getRetrain().setVectorProjectionMode("RAW_SHADOW_QUARANTINE");
        props.getRetrain().setMaxIngestLinesPerRun(10);

        String apiKey = "sk-" + "A".repeat(24);
        String bearer = "Bearer " + "private.owner.token";
        String supabaseKey = "sb_secret_" + "trainrag01";
        Path jsonl = tempDir.resolve("train_rag_raw_shadow_redacted.jsonl");
        Files.writeString(jsonl, String.format("""
                {"question":"legacy raw question api_key=%s","answer":"legacy raw answer %s %s","sessionId":"s1","validation":{"accepted":true,"sampleScore":0.91,"contaminationScore":0.01,"legacyContextScore":0.0,"rejectReasons":[],"thresholds":{"sampleScoreMin":0.55,"contaminationMax":0.30,"contextContaminationMax":0.35,"contradictionMax":0.60},"anomalies":{"flags":[]},"feedback":{"vectorDecision":"SHADOW_REVIEW"}}}
                """, apiKey, bearer, supabaseKey));

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(1, count);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), textCaptor.capture(), metaCaptor.capture());
        String text = textCaptor.getValue();
        assertTrue(text.contains("legacy raw question"));
        assertTrue(text.contains("legacy raw answer"));
        assertFalse(text.contains(apiKey));
        assertFalse(text.contains(bearer));
        assertFalse(text.contains(supabaseKey));
        assertEquals("RAW_SHADOW_QUARANTINE", metaCaptor.getValue().get("vector_projection_mode"));
    }

    @Test
    void noneProjectionSkipsVectorEnqueue() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(tempDir.resolve("ingest_state_none.json").toString());
        props.getRetrain().setVectorProjectionMode("NONE");
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("train_rag_none.jsonl");
        Files.writeString(jsonl, """
                {"question":"q","answer":"a","sessionId":"s1","validation":{"accepted":true,"rejectReasons":[],"thresholds":{"contaminationMax":0.30,"contextContaminationMax":0.35},"anomalies":{"flags":[]},"feedback":{"vectorDecision":"SHADOW_REVIEW"}}}
                """);

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        int count = service.ingestNewSamples(jsonl, "ds", () -> false);

        assertEquals(0, count);
        verify(vectorStoreService, never()).enqueue(anyString(), anyString(), anyString(), any());
        verify(vectorStoreService, never()).flush();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) TraceStore.get("uaw.retrain.ingest.summary");
        assertEquals("vector_projection_none", summary.get("reason"));
        assertEquals(0, summary.get("queuedDocs"));
        assertEquals(1, summary.get("parsedLines"));
    }

    @Test
    void ingestStateStoresDatasetPathHashInsteadOfRawAbsolutePath() throws Exception {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorSidService vectorSidService = mock(VectorSidService.class);
        UawAutolearnProperties props = new UawAutolearnProperties();
        Path statePath = tempDir.resolve("ingest_state_redacted.json");
        props.getRetrain().setIngestStatePath(statePath.toString());
        props.getRetrain().setMaxIngestLinesPerRun(10);

        Path jsonl = tempDir.resolve("private").resolve("train_rag_state.jsonl");
        Files.createDirectories(jsonl.getParent());
        Files.writeString(jsonl, """
                {"question":"q","answer":"a","sessionId":"s1","validation":{"accepted":true,"rejectReasons":[],"thresholds":{"contaminationMax":0.30,"contextContaminationMax":0.35},"anomalies":{"flags":[]},"feedback":{"vectorDecision":"SHADOW_REVIEW"}}}
                """);

        TrainRagIngestService service = new TrainRagIngestService(vectorStoreService, vectorSidService, props);
        assertEquals(1, service.ingestNewSamples(jsonl, "ds", () -> false));

        String state = Files.readString(statePath);
        String absolute = jsonl.toAbsolutePath().toString();
        assertFalse(state.contains(absolute));
        assertTrue(state.contains("\"fileHash\""));
        assertTrue(state.contains(SafeRedactor.hashValue(absolute)));
        assertTrue(state.contains("\"fileLength\""));
    }
}
