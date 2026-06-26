package com.example.lms.search.probe;

import ai.abandonware.nova.orch.failpattern.FailurePatternMatch;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CausalProbeTriggerServiceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void triggersReadyOnlyAfterThreeSamplesAndTwoAxesAgree() {
        DebugEventStore debugStore = new DebugEventStore();
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(debugStore), provider(null), provider(null));
        TraceStore.put("rawQuery", "raw query must not leak");
        TraceStore.put("Author" + "ization", "raw-auth-value-must-not-leak");
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);
        RagFailureBlackboxService.Snapshot blackbox =
                RagFailureBlackboxService.analyze(TraceStore.getAll());

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                "private goal value must not leak",
                "unit-test",
                blackbox,
                List.of());

        assertTrue(decision.evidenceReady());
        assertEquals("after_filter_starvation", decision.dominantFailure());
        assertEquals("web_filter", decision.hotspot());
        assertTrue(decision.confidence() >= 0.65d);
        assertEquals("anchor_compression_topup", decision.patchCandidate());
        assertEquals("source_patch_candidate", decision.action());
        assertEquals(Boolean.TRUE, TraceStore.get("causalProbe.evidenceReady"));
        assertEquals(3L, TraceStore.get("causalProbe.sampleCount"));
        assertEquals("after_filter_starvation", TraceStore.get("causalProbe.dominantFailure"));
        assertNotEquals("private goal value must not leak", TraceStore.get("causalProbe.goalHash"));

        String publicPayload = TraceStore.getByPrefix("causalProbe.").toString()
                + debugStore.list(10).toString();
        assertFalse(publicPayload.contains("raw query must not leak"), publicPayload);
        assertFalse(publicPayload.contains("raw-auth-value-must-not-leak"), publicPayload);
        assertFalse(publicPayload.contains("private goal value must not leak"), publicPayload);
        assertTrue(debugStore.list(10).stream()
                .map(DebugEvent::fingerprint)
                .anyMatch("causal_probe:after_filter_starvation:source_patch_candidate"::equals));
    }

    @Test
    void keepsProviderDisabledAsObserveOnlyInsteadOfPatchCandidate() {
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(new DebugEventStore()), provider(null), provider(null));
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.naver.disabledReason", "missing_key");
        RagFailureBlackboxService.Snapshot blackbox =
                RagFailureBlackboxService.analyze(TraceStore.getAll());

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                "credential repair goal",
                "provider-disabled-test",
                blackbox,
                List.of());

        assertTrue(decision.evidenceReady());
        assertEquals("provider_disabled", decision.dominantFailure());
        assertEquals("observe_provider_disabled", decision.patchCandidate());
        assertEquals("observe_only", decision.action());
        assertEquals("observe_only", TraceStore.get("causalProbe.action"));
    }

    @Test
    void needleOutcomeRewarderProjectsCausalProbeFromProbeSeam() {
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(new DebugEventStore()), provider(null), provider(null));
        NeedleOutcomeRewarder rewarder = new NeedleOutcomeRewarder();
        ReflectionTestUtils.setField(rewarder, "causalProbeTriggerService", service);
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);

        rewarder.recordOutcome(NeedleContribution.of(1, 0, -0.10d), -0.2d);

        assertEquals(Boolean.TRUE, TraceStore.get("causalProbe.evidenceReady"));
        assertEquals("after_filter_starvation", TraceStore.get("causalProbe.dominantFailure"));
        assertEquals("source_patch_candidate", TraceStore.get("causalProbe.action"));
        assertEquals("needleoutcomerewarder.recordoutcome", TraceStore.get("causalProbe.where"));
    }

    @Test
    void refusesReadyWhenSampleCountIsTooLow() {
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(new DebugEventStore()), provider(null), provider(null));
        TraceStore.put("probe.sampleCount", 2);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);
        RagFailureBlackboxService.Snapshot blackbox =
                RagFailureBlackboxService.analyze(TraceStore.getAll());

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                "goal",
                "sample-low-test",
                blackbox,
                List.of());

        assertFalse(decision.evidenceReady());
        assertEquals("sample_count_below_threshold", decision.triggerReason());
        assertEquals(Boolean.FALSE, TraceStore.get("causalProbe.evidenceReady"));
    }

    @Test
    void refusesReadyWhenOnlyOneSignalAxisPointsAtFailure() {
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(new DebugEventStore()), provider(null), provider(null));
        TraceStore.put("probe.sampleCount", 3);
        RagFailureBlackboxService.Snapshot blackbox =
                new RagFailureBlackboxService.Snapshot(
                        0.82d,
                        0.82d,
                        "timeout",
                        "web",
                        Map.of(),
                        List.of(),
                        "pattern",
                        "cooldown_reorder",
                        0.72d,
                        "unit",
                        Map.of(),
                        "SHADOW_REVIEW",
                        true);

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                "goal",
                "one-axis-test",
                blackbox,
                List.of());

        assertFalse(decision.evidenceReady());
        assertEquals("axis_agreement_below_threshold", decision.triggerReason());
        assertEquals(Boolean.FALSE, TraceStore.get("causalProbe.evidenceReady"));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
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
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
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
