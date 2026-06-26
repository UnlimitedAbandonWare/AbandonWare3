package com.nova.protocol.fusion;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.fusion.TailWeightedPowerMeanFuser;
import com.example.lms.service.rag.fusion.WeightedPowerMeanFuser;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import org.junit.jupiter.api.AfterEach;
import com.nova.protocol.alloc.SimpleRiskKAllocator;
import com.nova.protocol.properties.NovaNextProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaNextFusionServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void upperTailCandidateRisesOnlyInsideGuardBand() {
        NovaNextProperties props = new NovaNextProperties();
        NovaNextFusionService service = new NovaNextFusionService(props);
        NovaNextFusionService.ScoredResult tail = scored("tail", 1.0d, 0.95d);
        tail.setAuthorityAvg(0.90d);
        tail.setStrongCitationRate(1.0d);
        tail.setGrandasReadiness(0.90d);
        NovaNextFusionService.ScoredResult normal = scored("normal", 0.40d, 0.10d);

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(tail, normal));

        assertTrue(out.get(0).getAdjustedScore() >= out.get(0).getBaseScore());
        assertTrue(out.get(0).getAdjustedScore()
                <= out.get(0).getBaseScore() * (1.0d + props.getGrandas().getMaxAdjustment()) + 1.0e-9d);
        assertTrue(out.get(0).getTailSignal() >= 0.70d);
        assertEquals(props.getGrandas().getBodeC(), TraceStore.get("nova.next.bodeClamp.c"));
        assertTrue(TraceStore.get("nova.next.bodeClamp.result") instanceof Double);
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.activated"));
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.active"));
        assertTrue(TraceStore.get("hypernova.twpmP") instanceof Double);
        assertEquals(props.getGrandas().getPMax(), (Double) TraceStore.get("hypernova.twpmP.max"), 1.0e-9d);
        assertTrue(TraceStore.get("hypernova.twpm.p") instanceof Double);
        assertEquals(0.25d, (Double) TraceStore.get("hypernova.twpm.tailFraction"), 1.0e-9d);
        assertEquals("upper", TraceStore.get("hypernova.twpm.mode"));
        assertTrue(TraceStore.get("hypernova.cvarFusedScore") instanceof Double);
        assertEquals(props.getLambdaCvar(), (Double) TraceStore.get("hypernova.cvarPhi"), 1.0e-9d);
        assertEquals(Boolean.FALSE, TraceStore.get("hypernova.dppApplied"));
        assertEquals("too_few_results",
                TraceStore.get("hypernova.dppDisabledReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("hypernova.finalGatePassed"));
        assertEquals("downstream_gate_callback_pending",
                TraceStore.get("hypernova.finalGateDisabledReason"));
    }

    @Test
    void dppRerankerAppliesWhenHypernovaHasEnoughCandidates() {
        NovaNextFusionService service = new NovaNextFusionService(
                new NovaNextProperties(),
                null,
                new DppDiversityReranker(),
                new TailWeightedPowerMeanFuser(new WeightedPowerMeanFuser()));

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(
                scored("alpha duplicate", 0.92d, 0.80d),
                scored("alpha duplicate extra", 0.91d, 0.78d),
                scored("beta independent", 0.88d, 0.75d),
                scored("gamma independent", 0.86d, 0.70d)));

        assertEquals(4, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.dppApplied"));
        assertEquals(4, TraceStore.get("hypernova.dppInputCount"));
        assertEquals(4, TraceStore.get("hypernova.dppOutputCount"));
        assertEquals("", TraceStore.get("hypernova.dppDisabledReason"));
    }

    @Test
    void novaNextFusionDelegatesTwpmToCanonicalFuser() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/nova/protocol/fusion/NovaNextFusionService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("twpmFuser.fuseUpperTail("));
        assertFalse(source.contains("private static double weightedPowerMean("));
    }

    @Test
    void ordinaryCandidateAlmostHolds() {
        NovaNextFusionService service = new NovaNextFusionService(new NovaNextProperties());
        NovaNextFusionService.ScoredResult normal = scored("normal", 0.50d, 0.10d);

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(normal));

        assertTrue(Math.abs(out.get(0).getAdjustedScore() - out.get(0).getBaseScore())
                <= out.get(0).getGuardBand());
    }

    @Test
    void riskKAllocatorDistributesBudgetAwayFromHighRiskCandidates() {
        NovaNextProperties props = new NovaNextProperties();
        props.setKTotal(12);
        NovaNextFusionService service = new NovaNextFusionService(props, new SimpleRiskKAllocator());
        NovaNextFusionService.ScoredResult highRisk = scored("high-risk", 0.92d, 0.80d);
        highRisk.setContradictionRate(0.95d);
        NovaNextFusionService.ScoredResult lowRisk = scored("low-risk", 0.88d, 0.70d);
        lowRisk.setContradictionRate(0.05d);

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(highRisk, lowRisk));

        assertEquals(12, out.get(0).getRiskKAllocation() + out.get(1).getRiskKAllocation());
        assertTrue(out.get(1).getRiskKAllocation() > out.get(0).getRiskKAllocation());
        assertEquals(Boolean.TRUE, TraceStore.get("nova.hypernova.riskK.used"));
        assertEquals(12, TraceStore.get("nova.hypernova.riskK.alloc.sum"));
        Map<?, ?> riskKAlloc = (Map<?, ?>) TraceStore.get("hypernova.riskKAlloc");
        assertEquals(12, riskKAlloc.get("sum"));
        assertEquals(12, riskKAlloc.get("totalK"));
    }

    @Test
    void outOfRangeSourceScoresAreIgnoredWithScaleMismatchTrace() {
        NovaNextFusionService service = new NovaNextFusionService(new NovaNextProperties());
        NovaNextFusionService.ScoredResult calibrated = scored("calibrated", 0.90d, 0.30d);
        calibrated.setSourceScores(List.of(0.80d, 0.85d));
        NovaNextFusionService.ScoredResult mixedScale = scored("mixed-scale", 0.89d, 0.30d);
        mixedScale.setSourceScores(List.of(42.0d, 87.0d));

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(calibrated, mixedScale));

        assertEquals(2, TraceStore.get("hypernova.sourceScoreScaleMismatchCount"));
        assertEquals("fallback_to_base_norm", TraceStore.get("hypernova.sourceScoreScaleMismatchPolicy"));
        assertTrue(out.get(1).getAdjustedScore() <= out.get(0).getAdjustedScore());
    }

    @Test
    void nullEmptyDisabledAndNanInputArePassThrough() {
        NovaNextFusionService service = new NovaNextFusionService(new NovaNextProperties());
        assertTrue(service.fuse(null).isEmpty());
        List<NovaNextFusionService.ScoredResult> empty = List.of();
        assertSame(empty, service.fuse(empty));

        NovaNextProperties disabled = new NovaNextProperties();
        disabled.setEnabled(false);
        NovaNextFusionService disabledService = new NovaNextFusionService(disabled);
        List<NovaNextFusionService.ScoredResult> input = List.of(new NovaNextFusionService.ScoredResult(Double.NaN));
        assertSame(input, disabledService.fuse(input));

        List<NovaNextFusionService.ScoredResult> nan = service.fuse(input);
        assertEquals(0.0d, nan.get(0).getAdjustedScore());
    }

    @Test
    void fusionFailSoftRecordsRedactedReason() {
        NovaNextProperties props = new NovaNextProperties();
        props.setKTotal(4);
        NovaNextFusionService service = new NovaNextFusionService(props,
                (logits, risk, totalK, temp, floor) -> {
                    throw new IllegalStateException("raw scorer detail should not leak");
                });
        List<NovaNextFusionService.ScoredResult> input = List.of(
                scored("tail", 0.90d, 0.90d),
                scored("normal", 0.60d, 0.20d));

        List<NovaNextFusionService.ScoredResult> out = service.fuse(input);

        assertSame(input, out);
        assertEquals("fuse", TraceStore.get("nova.next.failSoft.stage"));
        assertEquals(Boolean.TRUE, TraceStore.get("nova.next.failSoft"));
        assertEquals("IllegalStateException", TraceStore.get("nova.next.failSoft.errorType"));
        assertTrue(String.valueOf(TraceStore.get("nova.next.failSoft.errorMessageHash")).startsWith("hash:"));
        assertEquals("raw scorer detail should not leak".length(),
                TraceStore.get("nova.next.failSoft.errorMessageLength"));
        assertNull(TraceStore.get("nova.next.failSoft.message"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw scorer detail should not leak"));
    }

    @Test
    void clampAppliedOnlyChangesWhenClampAdjustsDelta() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/com/nova/protocol/fusion/NovaNextFusionService.java"));

        assertFalse(source.contains("clampApplied = true;\n            double target"),
                "clampApplied should not be unconditional for every fused row");
        assertTrue(source.contains("if (Math.abs(clampedDeltaNorm) > 1.0e-9d)"));
    }

    private static NovaNextFusionService.ScoredResult scored(String id, double score, double tail) {
        NovaNextFusionService.ScoredResult sr = new NovaNextFusionService.ScoredResult(score);
        sr.setId(id);
        sr.setBaseScore(score);
        sr.setSourceScores(List.of(score));
        sr.setSourceCount(1);
        sr.setTailSignal(tail);
        return sr;
    }
}
