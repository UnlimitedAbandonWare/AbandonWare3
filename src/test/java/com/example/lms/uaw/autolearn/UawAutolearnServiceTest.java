package com.example.lms.uaw.autolearn;

import com.example.lms.agent.CuriosityTriggerService;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.learn.CfvmKAllocationTuner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class UawAutolearnServiceTest {

    @TempDir
    Path tempDir;

    private UawAutolearnProperties props;
    private ChatService chatService;
    private UawDatasetWriter datasetWriter;
    private UawLearningAgentHandoffWriter handoffWriter;
    private MockEnvironment env;

    @Test
    void autolearnLogsDoNotWriteRawModelOrThrowableText() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("reason={} model={}"));
        assertFalse(source.contains("AutoLearn sample preempted: {}\", e.toString()"));
        assertFalse(source.contains("ChatService.continueChat failed: {}\", e.toString()"));
        assertFalse(source.contains("CFVM post-validation feedback skipped: {}\", e.toString()"));
        assertFalse(source.contains("memory reinforcement skipped: {}\", e.toString()"));
        assertFalse(source.contains("TraceStore.put(\"uaw.autolearn.externalQuota.disabledReason\", privacyBlock);"));
        assertFalse(source.contains("TraceStore.put(\"uaw.autolearn.externalQuota.disabledReason\",\n                        externalModelPolicy.disabledReason());"));
        assertTrue(source.contains("reason={} modelHash={} modelLength={}"));
        assertTrue(source.contains("com.example.lms.trace.SafeRedactor.hashValue(quota.model())"));
        assertFalse(source.contains("com.example.lms.trace.SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("AutoLearn sample preempted. errorHash={} errorLength={}"));
        assertTrue(source.contains("ChatService.continueChat failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("CFVM post-validation feedback skipped. errorHash={} errorLength={}"));
        assertTrue(source.contains("memory reinforcement skipped. errorHash={} errorLength={}"));
        assertTrue(source.contains("com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("TraceStore.put(\"uaw.autolearn.externalQuota.disabledReason\", safeReason(privacyBlock));"));
        assertTrue(source.contains("safeReason(externalModelPolicy.disabledReason())"));
    }

    @Test
    void autolearnServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnService.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "UAW autolearn service needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        GuardContextHolder.clear();
        props = new UawAutolearnProperties();
        props.setBatchSize(1);
        props.setDefaultSeeds(List.of("Why does cache invalidation cause stale search answers in a RAG service?"));
        props.getSeed().setAllowStaticFallback(true);
        props.setMinEvidenceCount(3);
        props.setMinContextDiversity(0.30d);
        chatService = mock(ChatService.class);
        datasetWriter = mock(UawDatasetWriter.class);
        handoffWriter = mock(UawLearningAgentHandoffWriter.class);
        env = new MockEnvironment()
                .withProperty("uaw.autolearn.strict.search-queries", "7")
                .withProperty("uaw.autolearn.strict.max-sources", "9")
                .withProperty("uaw.autolearn.strict.memory-mode", "ephemeral")
                .withProperty("uaw.autolearn.strict.search-mode", "FORCE_DEEP")
                .withProperty("uaw.autolearn.strict.temperature", "0.2");
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void runCycleUsesStrictContinueChatRequestAndInstallsPreemptionContext() {
        AtomicReference<ChatRequestDto> requestRef = new AtomicReference<>();
        AtomicReference<GuardContext> guardRef = new AtomicReference<>();
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            requestRef.set(inv.getArgument(0));
            guardRef.set(GuardContextHolder.get());
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);

        long deadline = System.nanoTime() + 60_000_000_000L;
        AutoLearnCycleResult result = service(null).runCycle(
                tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                deadline);

        assertEquals(1, result.attempted());
        assertEquals(1, result.acceptedCount());
        ChatRequestDto request = requestRef.get();
        assertTrue(Boolean.TRUE.equals(request.isUseWebSearch()));
        assertTrue(Boolean.TRUE.equals(request.isUseRag()));
        assertTrue(request.isUseVerification());
        assertTrue(Boolean.TRUE.equals(request.getOfficialSourcesOnly()));
        assertTrue(Boolean.TRUE.equals(request.getPrecisionSearch()));
        assertEquals(Boolean.FALSE, request.getAccumulation());
        assertEquals(SearchMode.FORCE_DEEP, request.getSearchMode());
        assertEquals(7, request.getSearchQueries());
        assertEquals(9, request.getWebTopK());

        GuardContext guard = guardRef.get();
        assertTrue(guard.planBool("uaw.autolearn", false));
        assertEquals("service-direct", guard.getPlanOverride("uaw.autolearn.pipeline"));
        assertEquals(deadline, guard.planLong("uaw.autolearn.deadlineNanos", 0L));
        assertInstanceOf(BooleanSupplier.class, guard.getPlanOverride("uaw.autolearn.preemptionSupplier"));
        verify(handoffWriter).recordSample(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(UawDatasetWriter.TrainingMetadata.class), org.mockito.ArgumentMatchers.eq(true));
        verify(handoffWriter).recordCycle(anyString(), anyString(), any(AutoLearnCycleResult.class),
                any(UawAutolearnQualityTracker.CycleDiagnostics.class));
    }

    @Test
    void runCycleEmitsCfvmPostValidationFeedbackWhenKeyAndArmExist() {
        CfvmKAllocationTuner tuner = mock(CfvmKAllocationTuner.class);
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            TraceStore.put("cfvm.kalloc.key", "cfvm9:t1");
            TraceStore.put("cfvm.kalloc.arm", "BASE");
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);

        service(tuner).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        verify(tuner).feedback(anyString(), anyString(), anyDouble());
        assertEquals("true", String.valueOf(TraceStore.get("cfvm.kalloc.postValidationFeedback")));
    }

    @Test
    void runCycleSkipsCfvmPostValidationFeedbackWithoutKeyAndArm() {
        CfvmKAllocationTuner tuner = mock(CfvmKAllocationTuner.class);
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);

        service(tuner).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        verify(tuner, never()).feedback(anyString(), anyString(), anyDouble());
    }

    @Test
    void runCycleClearsContradictionTraceBeforeEachSample() {
        TraceStore.put("learning.validation.contradictionScore", 0.99d);
        TraceStore.put("learning.validation.contradictionCause", "evidence_conflict");
        TraceStore.put("overdrive.contradiction.mean", 0.99d);
        TraceStore.put("overdrive.reason", "threshold");
        TraceStore.put("extremez.risk.contradictionMean", 0.99d);
        TraceStore.put("extremez.risk.primaryCause", "evidence_conflict");
        TraceStore.put("extremez.activation.reason", "contradiction");
        TraceStore.put("rag.contradiction.score", 0.99d);

        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            assertNull(TraceStore.get("learning.validation.contradictionScore"));
            assertNull(TraceStore.get("learning.validation.contradictionCause"));
            assertNull(TraceStore.get("overdrive.contradiction.mean"));
            assertNull(TraceStore.get("overdrive.reason"));
            assertNull(TraceStore.get("extremez.risk.contradictionMean"));
            assertNull(TraceStore.get("extremez.risk.primaryCause"));
            assertNull(TraceStore.get("extremez.activation.reason"));
            assertNull(TraceStore.get("rag.contradiction.score"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(1, result.acceptedCount());
        assertEquals(0.0d, ((Number) TraceStore.get("learning.validation.contradictionScore")).doubleValue(), 0.0001d);
    }

    @Test
    void runCycleDoesNotAppendValidationRejectedSampleEvenWhenWriterWouldAccept() {
        props.setDefaultSeeds(List.of("Legal investment contract advice for a lawsuit"));
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of(
                            "alpha contract statute evidence source",
                            "beta lawsuit precedent evidence source",
                            "gamma investment risk evidence source",
                            "delta official filing evidence source",
                            "epsilon regulatory guidance evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(1, result.attempted());
        assertEquals(0, result.acceptedCount());
        assertEquals("rejected", TraceStore.getString("learning.validation.decision"));
        assertTrue(result.phaseFailures().containsKey("unconfirmed_high_risk_requery"));
        verify(datasetWriter, never()).append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class));
        verify(handoffWriter).recordSample(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(UawDatasetWriter.TrainingMetadata.class), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void runCycleRecordsDatasetWriteFailureForHandoff() {
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(false);

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        assertTrue(result.phaseFailures().containsKey("writer_failed"));
        verify(handoffWriter).recordSample(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(UawDatasetWriter.TrainingMetadata.class), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void runCycleContinuesWhenHandoffWriterThrows() {
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);
        doThrow(new RuntimeException("handoff boom")).when(handoffWriter).recordSample(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(),
                any(UawDatasetWriter.TrainingMetadata.class), org.mockito.ArgumentMatchers.eq(true));

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(1, result.acceptedCount());
        assertEquals("sample_write_failed", TraceStore.get("uaw.agent.handoff.status"));
    }

    @Test
    void runCycleDoesNotFallbackAfterFilterCountToEvidenceWhenProviderReportedZero() {
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            TraceStore.put("web.naver.returnedCount", 3);
            TraceStore.put("web.naver.afterFilterCount", 0);
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(1, result.attempted());
        assertEquals(0, result.acceptedCount());
        assertEquals(0L, TraceStore.getLong("learning.validation.afterFilterCount"));
        assertTrue(result.phaseFailures().containsKey("after_filter_starvation"));
        verify(datasetWriter, never()).append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class));
    }

    @Test
    void runCycleDoesNotFallbackAfterFilterCountToEvidenceWhenTavilyReportedZero() {
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            TraceStore.put("web.tavily.returnedCount", 3);
            TraceStore.put("web.tavily.afterFilterCount", 0);
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(1, result.attempted());
        assertEquals(0, result.acceptedCount());
        assertEquals(0L, TraceStore.getLong("learning.validation.afterFilterCount"));
        assertTrue(result.phaseFailures().containsKey("after_filter_starvation"));
        verify(datasetWriter, never()).append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class));
    }

    @Test
    void runCycleRejectsWebProviderDisabledCanonicalTrace() {
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            TraceStore.put("web.brave.disabledReasonCanonical", "missing_api_key");
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        assertTrue(result.phaseFailures().containsKey("provider_disabled"));
        assertTrue(String.valueOf(TraceStore.get("learning.validation.rejectReasons")).contains("provider_disabled"));
        verify(datasetWriter, never()).append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class));
    }

    @Test
    void runCycleRejectsTavilyProviderDisabledCanonicalTrace() {
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            TraceStore.put("web.tavily.disabledReasonCanonical", "missing_tavily_api_key");
            return ChatResult.of("supported answer", "gemma4:26b", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });
        when(datasetWriter.append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class))).thenReturn(true);

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        assertTrue(result.phaseFailures().containsKey("provider_disabled"));
        assertTrue(String.valueOf(TraceStore.get("learning.validation.rejectReasons")).contains("provider_disabled"));
        verify(datasetWriter, never()).append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class));
    }

    @Test
    void runCycleRecordsEmptyResultForHandoff() {
        when(chatService.continueChat(any(ChatRequestDto.class))).thenReturn(
                ChatResult.of(" ", "gemma4:26b", true, Set.of("alpha evidence source", "beta evidence source", "gamma evidence source")));

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        assertEquals("empty-response", TraceStore.get("uaw.autolearn.sampleSkipped.reason"));
        verify(handoffWriter).recordSkippedSample(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("SKIPPED"),
                org.mockito.ArgumentMatchers.eq("empty-response"));
    }

    @Test
    void missingHandoffWriterLeavesAbsentTraceInsteadOfSilentSkip() {
        when(chatService.continueChat(any(ChatRequestDto.class))).thenReturn(
                ChatResult.of(" ", "gemma4:26b", true, Set.of("alpha evidence source", "beta evidence source", "gamma evidence source")));

        AutoLearnCycleResult result = service(null, null, null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.handoffWriter.absent"));
    }

    @Test
    void runCycleRecordsInsufficientEvidenceForHandoff() {
        when(chatService.continueChat(any(ChatRequestDto.class))).thenReturn(
                ChatResult.of("supported answer", "gemma4:26b", true, Set.of("alpha evidence source")));

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        verify(handoffWriter).recordSkippedSample(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("SKIPPED"),
                org.mockito.ArgumentMatchers.eq("insufficient_evidence"));
    }

    @Test
    void runCycleRecordsLowContextDiversityForHandoff() {
        props.setMinContextDiversity(0.95d);
        when(chatService.continueChat(any(ChatRequestDto.class))).thenReturn(
                ChatResult.of("supported answer", "gemma4:26b", true, Set.of("aaaa", "aaab", "aaac")));

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        verify(handoffWriter).recordSkippedSample(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("SKIPPED"),
                org.mockito.ArgumentMatchers.eq("low_context_diversity"));
    }

    @Test
    void runCycleRecordsChatServiceExceptionForHandoff() {
        when(chatService.continueChat(any(ChatRequestDto.class))).thenThrow(new RuntimeException("chat failed"));

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        verify(handoffWriter).recordSkippedSample(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("ERROR"),
                org.mockito.ArgumentMatchers.eq("chat_service_exception"));
    }

    @Test
    void runCycleTracesCancellationWithoutRawExceptionText() {
        when(chatService.continueChat(any(ChatRequestDto.class)))
                .thenThrow(new CancellationException("ownerToken=cancel-secret"));

        AutoLearnCycleResult result = service(null).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertTrue(result.abortedByUser());
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.autolearn.cancelled"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=cancel-secret"));
        verify(handoffWriter, never()).recordSample(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(UawDatasetWriter.TrainingMetadata.class), any(Boolean.class));
    }

    @Test
    void runCycleSkipsExternalRouteWhenLocalFreeQuotaWouldBeExceeded() {
        props.getBudget().setStatePath(tempDir.resolve("autolearn_state.json").toString());
        props.getExternalQuota().setEnabled(true);
        props.getExternalQuota().setMaxOutputTokensPerDay(1);
        env.setProperty("uaw.autolearn.strict.model", "llmrouter.external");
        env.setProperty("llmrouter.models.external.enabled", "true");
        env.setProperty("llmrouter.models.external.name", "deepseek-v4-flash-free");
        env.setProperty("llmrouter.models.external.base-url", "https://opencode.ai/zen/v1");
        env.setProperty("OPENCODE_API_KEY", "opencode-secret-must-not-leak");
        OpenCodeFreeQuotaGuard guard = new OpenCodeFreeQuotaGuard(props, new AutoLearnRunStateStore(), env);

        AutoLearnCycleResult result = service(null, guard).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        verify(chatService, never()).continueChat(any(ChatRequestDto.class));
        verify(handoffWriter).recordSkippedSample(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.eq("deepseek-v4-flash-free"),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("SKIPPED"),
                org.mockito.ArgumentMatchers.eq("daily_token_limit"));
    }

    @Test
    void runCycleSkipsExternalRouteWhenOpenCodeKeyIsMissingBeforeCallingChatService() {
        props.getBudget().setStatePath(tempDir.resolve("autolearn_state.json").toString());
        props.getExternalQuota().setEnabled(true);
        env.setProperty("uaw.autolearn.strict.model", "llmrouter.external");
        env.setProperty("llmrouter.models.external.enabled", "true");
        env.setProperty("llmrouter.models.external.name", "deepseek-v4-flash-free");
        env.setProperty("llmrouter.models.external.base-url", "https://opencode.ai/zen/v1");
        OpenCodeFreeQuotaGuard guard = new OpenCodeFreeQuotaGuard(props, new AutoLearnRunStateStore(), env);

        AutoLearnCycleResult result = service(null, guard).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        assertTrue(result.phaseFailures().containsKey("missing_opencode_api_key"));
        assertEquals("external_quota_guard_denied", result.diagnosis());
        verify(chatService, never()).continueChat(any(ChatRequestDto.class));
        verify(handoffWriter).recordSkippedSample(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.eq("deepseek-v4-flash-free"),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("SKIPPED"),
                org.mockito.ArgumentMatchers.eq("missing_opencode_api_key"));
    }

    @Test
    void runCycleClampsExternalRouteAndKeepsFreeOutputOutOfCanonicalDataset() {
        props.getBudget().setStatePath(tempDir.resolve("autolearn_state.json").toString());
        props.getExternalQuota().setEnabled(true);
        props.getExternalQuota().setMaxOutputTokensPerCall(512);
        env.setProperty("uaw.autolearn.strict.model", "llmrouter.external");
        env.setProperty("uaw.autolearn.strict.max-tokens", "2048");
        env.setProperty("llmrouter.models.external.enabled", "true");
        env.setProperty("llmrouter.models.external.name", "deepseek-v4-flash-free");
        env.setProperty("llmrouter.models.external.base-url", "https://opencode.ai/zen/v1");
        env.setProperty("OPENCODE_API_KEY", "opencode-secret-must-not-leak");
        OpenCodeFreeQuotaGuard guard = new OpenCodeFreeQuotaGuard(props, new AutoLearnRunStateStore(), env);
        AtomicReference<ChatRequestDto> requestRef = new AtomicReference<>();
        when(chatService.continueChat(any(ChatRequestDto.class))).thenAnswer(inv -> {
            requestRef.set(inv.getArgument(0));
            TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
            TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
            TraceStore.put("selfask.3way.requery.confirmed", true);
            return ChatResult.of("supported answer", "deepseek-v4-flash-free", true,
                    Set.of("alpha evidence source", "beta evidence source", "gamma evidence source"));
        });

        AutoLearnCycleResult result = service(null, guard).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        assertEquals(512, requestRef.get().getMaxTokens());
        assertEquals("llmrouter.external", requestRef.get().getModel());
        assertEquals(false, requestRef.get().isUseWebSearch());
        assertEquals(false, requestRef.get().isUseRag());
        assertEquals(false, requestRef.get().isUseVerification());
        assertEquals(SearchMode.OFF, requestRef.get().getSearchMode());
        assertTrue(result.phaseFailures().containsKey("external_free_model_curate_only"));
        verify(datasetWriter, never()).append(any(File.class), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(UawDatasetWriter.TrainingMetadata.class));
        verify(handoffWriter).recordSample(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(UawDatasetWriter.TrainingMetadata.class), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void runCycleBlocksExternalRouteForTaggedGapSeedUnderStaticSyntheticPrivacyMode() {
        props.getBudget().setStatePath(tempDir.resolve("autolearn_state.json").toString());
        props.setDefaultSeeds(List.of("내부 자동학습: (gap) user-derived seed"));
        props.getSeed().setHistoryEnabled(false);
        props.getExternalQuota().setEnabled(true);
        env.setProperty("uaw.autolearn.strict.model", "llmrouter.external");
        env.setProperty("llmrouter.models.external.enabled", "true");
        env.setProperty("llmrouter.models.external.name", "deepseek-v4-flash-free");
        env.setProperty("llmrouter.models.external.base-url", "https://opencode.ai/zen/v1");
        env.setProperty("OPENCODE_API_KEY", "opencode-secret-must-not-leak");
        OpenCodeFreeQuotaGuard guard = new OpenCodeFreeQuotaGuard(props, new AutoLearnRunStateStore(), env);

        AutoLearnCycleResult result = service(null, guard).runCycle(tempDir.resolve("train_rag.jsonl").toFile(),
                "uaw-test-session",
                () -> false,
                System.nanoTime() + 60_000_000_000L);

        assertEquals(0, result.acceptedCount());
        assertTrue(result.phaseFailures().containsKey("external_privacy_block"));
        verify(chatService, never()).continueChat(any(ChatRequestDto.class));
    }

    private UawAutolearnService service(CfvmKAllocationTuner tuner) {
        return service(tuner, null);
    }

    private UawAutolearnService service(CfvmKAllocationTuner tuner, OpenCodeFreeQuotaGuard quotaGuard) {
        return service(tuner, quotaGuard, handoffWriter);
    }

    private UawAutolearnService service(CfvmKAllocationTuner tuner,
                                        OpenCodeFreeQuotaGuard quotaGuard,
                                        UawLearningAgentHandoffWriter activeHandoffWriter) {
        return new UawAutolearnService(
                chatService,
                props,
                null,
                datasetWriter,
                new LearningSampleValidationMetadataBuilder(),
                provider(activeHandoffWriter),
                new UawAutolearnQualityTracker(props, null, null),
                provider(null),
                provider(tuner),
                provider(quotaGuard),
                env,
                null,
                provider(null));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public java.util.Iterator<T> iterator() {
                return value == null ? Collections.emptyIterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
