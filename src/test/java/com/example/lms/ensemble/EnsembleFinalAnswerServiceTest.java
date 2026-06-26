package com.example.lms.ensemble;

import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnsembleFinalAnswerServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void disabledFeatureSkipsSamplingAndLeavesSinglePathAvailable() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);

        assertTrue(service.tryGenerate(PromptContext.builder().userQuery("q").build(), 7L).isEmpty());
        assertEquals("disabled", TraceStore.get("ensemble.sampling.skipped"));
        assertEquals("disabled", TraceStore.get("ensemble.bypass.reason"));
        verify(sampler, never()).sample(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void enabledFeatureRecordsNoCandidateBypassReason() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        when(sampler.sample(ctx, "session-7")).thenReturn(List.of());

        assertTrue(service.tryGenerate(ctx, 7L).isEmpty());

        assertEquals("no_candidates", TraceStore.get("ensemble.judge.skipped"));
        assertEquals("no_candidates", TraceStore.get("ensemble.bypass.reason"));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void enabledFeatureSamplesCandidatesAndReturnsJudgeDraft() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        SampledCandidate candidate = new SampledCandidate(
                "deterministic",
                "candidate answer",
                0.4d,
                0.4d,
                0.9d,
                0.1d,
                FinalSigmoidGate.GateResult.PASS);
        when(sampler.sample(ctx, "session-7")).thenReturn(List.of(candidate));
        when(judge.judge(List.of(candidate), ctx, "session-7")).thenReturn("judge answer");

        assertEquals("judge answer", service.tryGenerate(ctx, 7L).orElseThrow());
        assertEquals(Boolean.TRUE, TraceStore.get("ensemble.used"));
        assertEquals(12, TraceStore.get("ensemble.resultLen"));
    }

    @Test
    void enabledFeatureFallsBackWhenJudgeReturnsBlank() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        SampledCandidate candidate = new SampledCandidate(
                "explore",
                "candidate answer",
                1.4d,
                0.9d,
                0.9d,
                0.1d,
                FinalSigmoidGate.GateResult.PASS);
        when(sampler.sample(ctx, "session-none")).thenReturn(List.of(candidate));
        when(judge.judge(List.of(candidate), ctx, "session-none")).thenReturn(" ");

        assertTrue(service.tryGenerate(ctx, null).isEmpty());
        assertEquals("blank_result", TraceStore.get("ensemble.bypass.reason"));
    }

    @Test
    void enabledFeatureFailureRecordsStableBypassReason() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        when(sampler.sample(ctx, "session-7")).thenThrow(new IllegalStateException("sampler down"));

        assertTrue(service.tryGenerate(ctx, 7L).isEmpty());

        assertEquals("ensemble_final_answer_failed", TraceStore.get("ensemble.bypass.reason"));
        assertFalse(String.valueOf(TraceStore.get("ensemble.bypass.reason")).contains("IllegalStateException"));
    }
}
