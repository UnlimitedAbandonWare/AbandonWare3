package com.example.lms.harmony;

import com.example.lms.search.TraceStore;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HarmonyScoreEngineTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void subsystemGoalsAddUpToDirectiveGoalPoint() {
        assertEquals(8, SubsystemGoalTable.GOALS.size());
        assertEquals(100.0d, SubsystemGoalTable.totalGoalPoint(), 0.0001d);
    }

    @Test
    void subsystemGoalTablePreservesDashboardOrder() {
        assertEquals(List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08"),
                List.copyOf(SubsystemGoalTable.GOALS.keySet()));
    }

    @Test
    void snapshotBuilderClampsInvalidScoreFieldsToDirectiveRange() {
        HarmonyScoreSnapshot snapshot = HarmonyScoreSnapshot.builder()
                .harmonyScore(Double.NaN)
                .contaminationScore(125.0d)
                .achievementPct(-7.0d)
                .goalPoint(Double.POSITIVE_INFINITY)
                .build();

        assertEquals(0.0d, snapshot.harmonyScore(), 0.0001d);
        assertEquals(100.0d, snapshot.contaminationScore(), 0.0001d);
        assertEquals(0.0d, snapshot.achievementPct(), 0.0001d);
        assertEquals(100.0d, snapshot.goalPoint(), 0.0001d);
    }

    @Test
    void computesPerfectHarmonyWhenAllDirectiveTraceKeysArePresent() {
        TraceStore.put("ablation.penalties", List.of());
        TraceStore.put("retrievalOrder.lastSetBy", "MoE");
        TraceStore.put("boosterMode.active", "OVERDRIVE");
        TraceStore.put("hypernova.twpmP", 1.25d);
        TraceStore.put("hypernova.cvarPhi", 0.618d);
        TraceStore.put("ablation.score.current", 0.0d);
        TraceStore.put("extremeZ.cancelShieldWrapped", Boolean.TRUE);
        TraceStore.put("cfvm.boltzmannTemp", 0.72d);
        TraceStore.put("cfvm.tempAnnealApplied", Boolean.TRUE);
        TraceStore.put("moe.evolverPlateRegistered", Boolean.TRUE);
        TraceStore.put("extremeZ.timeBudgetConsumedMs", 37L);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", Boolean.TRUE);
        TraceStore.put("outCount", 4);

        HarmonyScoreSnapshot snapshot = new HarmonyScoreEngine(
                new HarmonyBreakLedger(),
                new ContaminationAccumulator())
                .compute();

        assertEquals(100.0d, snapshot.harmonyScore(), 0.0001d);
        assertEquals(0.0d, snapshot.contaminationScore(), 0.0001d);
        assertEquals(100.0d, snapshot.achievementPct(), 0.0001d);
        assertEquals(12, snapshot.harmonyBreaks().size());
        assertTrue(snapshot.harmonyBreaks().stream().allMatch(entry -> "DONE".equals(entry.status())));
    }

    @Test
    void openBreaksSubtractPenaltiesAndContaminationSignalsClampAtHundred() {
        TraceStore.put("ablation.penalties", List.of(
                Map.of("stage", "p01"), Map.of("stage", "p02"), Map.of("stage", "p03"),
                Map.of("stage", "p04"), Map.of("stage", "p05"), Map.of("stage", "p06"),
                Map.of("stage", "p07"), Map.of("stage", "p08"), Map.of("stage", "p09"),
                Map.of("stage", "p10")));
        TraceStore.put("outCount", 0);
        TraceStore.put("starvationFallback.trigger", "all_skipped");
        TraceStore.put("queryTransformer.bypassed", Boolean.TRUE);
        TraceStore.put("extremeZ.cancelShieldWrapped", Boolean.FALSE);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", Boolean.FALSE);
        TraceStore.put("boosterMode.exclusionReason", "conflict");

        HarmonyScoreSnapshot snapshot = new HarmonyScoreEngine(
                new HarmonyBreakLedger(),
                new ContaminationAccumulator())
                .compute();

        assertEquals(0.0d, snapshot.harmonyScore(), 0.0001d);
        assertEquals(100.0d, snapshot.contaminationScore(), 0.0001d);
        assertTrue(snapshot.harmonyBreaks().stream()
                .anyMatch(entry -> "HB-02".equals(entry.id()) && "OPEN".equals(entry.status())));
        assertTrue(snapshot.harmonyBreaks().stream()
                .anyMatch(entry -> "HB-07".equals(entry.id()) && "OPEN".equals(entry.status())));
        assertTrue(snapshot.harmonyBreaks().stream()
                .anyMatch(entry -> "HB-12".equals(entry.id()) && "OPEN".equals(entry.status())));
        assertTrue(snapshot.topContaminants().contains("starvation_outCount"));
        assertTrue(snapshot.nextGoalHint().contains("HB-02"));
    }

    @Test
    void breakLedgerReadsRetrievalOrderServiceTraceFromRecentSnapshot() {
        new RetrievalOrderService().decideOrder("RAG evidence starvation debug");
        Object lastSetBy = TraceStore.get("retrievalOrder.lastSetBy");
        Object lastOrder = TraceStore.get("retrievalOrder.lastOrder");
        TraceStore.clear();

        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        TraceSnapshotStore.TraceSnapshot snapshot = new TraceSnapshotStore.TraceSnapshot(
                "snapshot-1",
                1L,
                "2026-06-20T00:00:00Z",
                "sidHash",
                "sessionHash",
                "traceHash",
                "requestHash",
                "http_request",
                "POST",
                "/api/chat",
                200,
                null,
                true,
                2,
                Map.of(),
                Map.of(
                        "retrievalOrder.lastSetBy", lastSetBy,
                        "retrievalOrder.lastOrder", lastOrder),
                Map.of(),
                null,
                false);
        when(store.listSummaries(20)).thenReturn(List.of(Map.of("id", "snapshot-1")));
        when(store.get("snapshot-1")).thenReturn(Optional.of(snapshot));

        HarmonyBreakLedger ledger = new HarmonyBreakLedger(mockProvider(store));

        List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks = ledger.evaluate();

        assertTrue(breaks.stream()
                .anyMatch(entry -> "HB-02".equals(entry.id())
                        && "DONE".equals(entry.status())
                        && entry.evidence().contains("recentSnapshot")));
    }

    @Test
    void breakLedgerTraceReadFailureLeavesRedactedBreadcrumb() {
        HarmonyBreakLedger ledger = new HarmonyBreakLedger(new HarmonyTraceReader() {
            @Override
            public TraceRead read(String key) {
                throw new IllegalStateException("raw-secret-message");
            }
        });

        List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks = ledger.evaluate();

        assertTrue(breaks.stream()
                .anyMatch(entry -> "UNKNOWN".equals(entry.status())));
        assertEquals(Boolean.TRUE, TraceStore.get("harmony.breakLedger.traceRead.failed"));
        assertEquals("IllegalStateException", TraceStore.get("harmony.breakLedger.traceRead.errorType"));
        assertTrue(!String.valueOf(TraceStore.getAll()).contains("raw-secret-message"));
    }

    @Test
    void contaminationAccumulatorReadsRecentSnapshotWhenCurrentRequestTraceIsMissing() {
        HarmonyTraceReader reader = traceReaderWithSnapshot(Map.of(
                "outCount", 0,
                "starvationFallback.trigger", "all_skipped",
                "queryTransformer.bypassed", Boolean.TRUE));
        ContaminationAccumulator accumulator = new ContaminationAccumulator(reader);

        assertEquals(45.0d, accumulator.compute(), 0.0001d);
        assertEquals(List.of("starvation_outCount", "provider_all_skipped", "qt_bypassed"),
                accumulator.topContaminants(3));
    }

    @Test
    void singlePrimaryModeExclusionReasonIsNotBoosterContamination() {
        TraceStore.put("boosterMode.exclusionReason", "single_primary_mode:EXTREMEZ>HYPERNOVA>OVERDRIVE");

        ContaminationAccumulator accumulator = new ContaminationAccumulator();

        assertEquals(0.0d, accumulator.compute(), 0.0001d);
        assertTrue(accumulator.topContaminants(3).isEmpty());
    }

    @Test
    void resolvedBoosterConflictSnapshotDoesNotCountAsContaminationAfterRedaction() {
        HarmonyTraceReader reader = traceReaderWithSnapshot(Map.of(
                "boosterMode.exclusionReason", "hash:resolved-single-primary",
                "boosterMode.conflictResolved", Boolean.TRUE,
                "boosterMode.excludedModes", List.of("OVERDRIVE", "HYPERNOVA")));

        ContaminationAccumulator accumulator = new ContaminationAccumulator(reader);

        assertEquals(0.0d, accumulator.compute(), 0.0001d);
        assertTrue(accumulator.topContaminants(3).isEmpty());
    }

    @Test
    void normalPlanSnapshotDoesNotCountHashedExclusionReasonAsContamination() {
        HarmonyTraceReader reader = traceReaderWithSnapshot(Map.of(
                "boosterMode.exclusionReason", "hash:normal-single-primary",
                "boosterMode.conflictResolved", Boolean.FALSE,
                "boosterMode.excludedModes", List.of()));

        ContaminationAccumulator accumulator = new ContaminationAccumulator(reader);

        assertEquals(0.0d, accumulator.compute(), 0.0001d);
        assertTrue(accumulator.topContaminants(3).isEmpty());
    }

    @Test
    void unresolvedBoosterConflictSnapshotCountsAsContaminationWhenResolutionIsFalse() {
        HarmonyTraceReader reader = traceReaderWithSnapshot(Map.of(
                "boosterMode.exclusionReason", "hash:unresolved-conflict",
                "boosterMode.conflictResolved", Boolean.FALSE,
                "boosterMode.excludedModes", List.of("OVERDRIVE")));

        ContaminationAccumulator accumulator = new ContaminationAccumulator(reader);

        assertEquals(10.0d, accumulator.compute(), 0.0001d);
        assertEquals(List.of("booster_conflict"), accumulator.topContaminants(3));
    }

    @Test
    void computeFailureLeavesTraceBreadcrumb() {
        HarmonyScoreEngine engine = new HarmonyScoreEngine(new HarmonyBreakLedger() {
            @Override
            public List<HarmonyScoreSnapshot.HarmonyBreakEntry> evaluate() {
                throw new RuntimeException("synthetic failure");
            }
        }, new ContaminationAccumulator());

        HarmonyScoreSnapshot snapshot = engine.compute();

        assertEquals(0.0d, snapshot.harmonyScore(), 0.0001d);
        assertEquals(Boolean.TRUE, TraceStore.get("harmony.score.compute.failed"));
        assertEquals("RuntimeException", TraceStore.get("harmony.score.compute.errorType"));
    }

    private static HarmonyTraceReader traceReaderWithSnapshot(Map<String, Object> trace) {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        TraceSnapshotStore.TraceSnapshot snapshot = new TraceSnapshotStore.TraceSnapshot(
                "snapshot-1",
                1L,
                "2026-06-20T00:00:00Z",
                "sidHash",
                "sessionHash",
                "traceHash",
                "requestHash",
                "http_request",
                "POST",
                "/api/chat",
                200,
                null,
                true,
                trace.size(),
                Map.of(),
                trace,
                Map.of(),
                null,
                false);
        when(store.listSummaries(20)).thenReturn(List.of(Map.of("id", "snapshot-1")));
        when(store.get("snapshot-1")).thenReturn(Optional.of(snapshot));
        return new HarmonyTraceReader(mockProvider(store));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TraceSnapshotStore> mockProvider(TraceSnapshotStore store) {
        ObjectProvider<TraceSnapshotStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }
}
