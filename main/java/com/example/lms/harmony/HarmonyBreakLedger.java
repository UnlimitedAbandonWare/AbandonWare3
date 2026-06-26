package com.example.lms.harmony;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HarmonyBreakLedger {

    private static final List<HbDef> DEFINITIONS = List.of(
            new HbDef("HB-01", 35.6d, "ablation.penalties", "Silent catch contamination"),
            new HbDef("HB-02", 32.0d, "retrievalOrder.lastSetBy", "RetrievalOrderService authority missing"),
            new HbDef("HB-03", 24.0d, "boosterMode.active", "Booster trigger conflict"),
            new HbDef("HB-04", 22.4d, "hypernova.twpmP", "DPP-HYPERNOVA integration missing"),
            new HbDef("HB-05", 21.0d, "hypernova.cvarPhi", "TWPM/CVaR canonical path duplicate"),
            new HbDef("HB-06", 20.0d, "ablation.score.current", "Score scale normalization missing"),
            new HbDef("HB-07", 18.0d, "extremeZ.cancelShieldWrapped", "CancelShield interrupt propagation"),
            new HbDef("HB-08", 16.8d, "cfvm.boltzmannTemp", "CFVM Boltzmann temperature split"),
            new HbDef("HB-09", 14.0d, "cfvm.tempAnnealApplied", "CFVM raw tile temperature disabled"),
            new HbDef("HB-10", 12.0d, "moe.evolverPlateRegistered", "MoE evolver bypass risk"),
            new HbDef("HB-11", 11.2d, "extremeZ.timeBudgetConsumedMs", "TimeBudgetGuard trace missing"),
            new HbDef("HB-12", 10.5d, "cihRag.breadcrumb.queryRedacted", "Breadcrumb redaction missing"));

    private final HarmonyTraceReader traceReader;

    public HarmonyBreakLedger() {
        this(new HarmonyTraceReader());
    }

    @Autowired
    public HarmonyBreakLedger(HarmonyTraceReader traceReader) {
        this.traceReader = traceReader == null ? new HarmonyTraceReader() : traceReader;
    }

    public HarmonyBreakLedger(ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider) {
        this(new HarmonyTraceReader(traceSnapshotStoreProvider));
    }

    public List<HarmonyScoreSnapshot.HarmonyBreakEntry> evaluate() {
        return DEFINITIONS.stream()
                .map(this::evaluateOne)
                .toList();
    }

    public double totalPenalty(List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks) {
        if (breaks == null) {
            return DEFINITIONS.stream().mapToDouble(HbDef::penalty).sum();
        }
        return breaks.stream()
                .filter(entry -> "OPEN".equals(entry.status()))
                .mapToDouble(HarmonyScoreSnapshot.HarmonyBreakEntry::penaltyScore)
                .sum();
    }

    private HarmonyScoreSnapshot.HarmonyBreakEntry evaluateOne(HbDef definition) {
        try {
            HarmonyTraceReader.TraceRead read = traceReader.read(definition.traceKey());
            if (isDoneValue(read.value())) {
                return new HarmonyScoreSnapshot.HarmonyBreakEntry(
                        definition.id(),
                        "DONE",
                        definition.penalty(),
                        definition.traceKey() + "=" + read.evidenceSource());
            }
            return new HarmonyScoreSnapshot.HarmonyBreakEntry(
                    definition.id(),
                    "OPEN",
                    definition.penalty(),
                    "evidence_needed: TraceStore key missing key=" + definition.traceKey());
        } catch (RuntimeException error) {
            TraceStore.put("harmony.breakLedger.traceRead.failed", Boolean.TRUE);
            TraceStore.put("harmony.breakLedger.traceRead.key", definition.traceKey());
            TraceStore.put("harmony.breakLedger.traceRead.errorType", error.getClass().getSimpleName());
            return new HarmonyScoreSnapshot.HarmonyBreakEntry(
                    definition.id(),
                    "UNKNOWN",
                    definition.penalty(),
                    "evidence_needed: TraceStore.get failed type=" + error.getClass().getSimpleName());
        }
    }

    private static boolean isDoneValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return !text.isBlank() && !"false".equalsIgnoreCase(text.trim());
        }
        return true;
    }

    private record HbDef(String id, double penalty, String traceKey, String description) {
    }
}
