package com.example.lms.harmony;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HarmonyScoreEngine {

    private static final Map<String, String> HB_TO_SUBSYSTEM = Map.ofEntries(
            Map.entry("HB-01", "S01"),
            Map.entry("HB-02", "S03"),
            Map.entry("HB-03", "S05"),
            Map.entry("HB-04", "S06"),
            Map.entry("HB-05", "S06"),
            Map.entry("HB-06", "S08"),
            Map.entry("HB-07", "S05"),
            Map.entry("HB-08", "S02"),
            Map.entry("HB-09", "S02"),
            Map.entry("HB-10", "S03"),
            Map.entry("HB-11", "S05"),
            Map.entry("HB-12", "S04"));

    private final HarmonyBreakLedger breakLedger;
    private final ContaminationAccumulator contaminationAccumulator;
    private final HarmonyTraceReader traceReader;

    public HarmonyScoreEngine(HarmonyBreakLedger breakLedger, ContaminationAccumulator contaminationAccumulator) {
        this(breakLedger, contaminationAccumulator, new HarmonyTraceReader());
    }

    @Autowired
    public HarmonyScoreEngine(
            HarmonyBreakLedger breakLedger,
            ContaminationAccumulator contaminationAccumulator,
            HarmonyTraceReader traceReader) {
        this.breakLedger = breakLedger;
        this.contaminationAccumulator = contaminationAccumulator;
        this.traceReader = traceReader == null ? new HarmonyTraceReader() : traceReader;
    }

    public HarmonyScoreSnapshot compute() {
        try {
            return computeSafely();
        } catch (RuntimeException error) {
            TraceStore.put("harmony.score.compute.failed", Boolean.TRUE);
            TraceStore.put("harmony.score.compute.errorType", error.getClass().getSimpleName());
            return HarmonyScoreSnapshot.builder()
                    .calculatedAt(Instant.now())
                    .harmonyScore(0.0d)
                    .contaminationScore(100.0d)
                    .achievementPct(0.0d)
                    .goalPoint(SubsystemGoalTable.totalGoalPoint())
                    .nextGoalHint("evidence_needed: harmony score compute failed type="
                            + error.getClass().getSimpleName())
                    .build();
        }
    }

    private HarmonyScoreSnapshot computeSafely() {
        List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks = breakLedger.evaluate();
        double rawHarmony = 100.0d - breakLedger.totalPenalty(breaks);
        double harmonyScore = clamp(rawHarmony + synergyBonus());
        double contaminationScore = contaminationAccumulator.compute();
        double goalPoint = SubsystemGoalTable.totalGoalPoint();
        double achievementPct = goalPoint > 0.0d ? clamp(harmonyScore / goalPoint * 100.0d) : 0.0d;

        return HarmonyScoreSnapshot.builder()
                .calculatedAt(Instant.now())
                .harmonyScore(harmonyScore)
                .contaminationScore(contaminationScore)
                .achievementPct(achievementPct)
                .goalPoint(goalPoint)
                .subsystemScores(subsystemScores(breaks))
                .harmonyBreaks(breaks)
                .topContaminants(contaminationAccumulator.topContaminants(7))
                .nextGoalHint(nextGoalHint(breaks))
                .build();
    }

    private double synergyBonus() {
        double bonus = 0.0d;
        if (contains("boosterMode.active", "OVERDRIVE") && truthy("extremeZ.cancelShieldWrapped")) {
            bonus += 5.0d;
        }
        if (present("cfvm.boltzmannTemp") && truthy("moe.evolverPlateRegistered")) {
            bonus += 4.0d;
        }
        if (present("hypernova.twpmP") && truthy("cihRag.breadcrumb.queryRedacted")) {
            bonus += 4.0d;
        }
        return Math.min(13.0d, bonus);
    }

    private Map<String, HarmonyScoreSnapshot.SubsystemScore> subsystemScores(
            List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks) {
        Map<String, Double> current = new LinkedHashMap<>();
        SubsystemGoalTable.GOALS.forEach((id, goal) -> current.put(id, goal.goalPoint()));

        for (HarmonyScoreSnapshot.HarmonyBreakEntry entry : breaks) {
            if (!"OPEN".equals(entry.status())) {
                continue;
            }
            String subsystem = HB_TO_SUBSYSTEM.get(entry.id());
            if (subsystem == null || !current.containsKey(subsystem)) {
                continue;
            }
            double goal = SubsystemGoalTable.GOALS.get(subsystem).goalPoint();
            double next = Math.max(0.0d, current.get(subsystem) - (entry.penaltyScore() * goal / 100.0d));
            current.put(subsystem, next);
        }

        Map<String, HarmonyScoreSnapshot.SubsystemScore> out = new LinkedHashMap<>();
        SubsystemGoalTable.GOALS.forEach((id, goal) -> out.put(id,
                new HarmonyScoreSnapshot.SubsystemScore(
                        id,
                        goal.name(),
                        round2(goal.goalPoint()),
                        round2(current.getOrDefault(id, 0.0d)))));
        return out;
    }

    private static String nextGoalHint(List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks) {
        return breaks.stream()
                .filter(entry -> "OPEN".equals(entry.status()))
                .max(Comparator.comparingDouble(HarmonyScoreSnapshot.HarmonyBreakEntry::penaltyScore))
                .map(entry -> "Next patch target: " + entry.id() + " (" + entry.evidence() + ")")
                .orElse("All HB checks are DONE; maintain harmony score and monitor contamination.");
    }

    private boolean present(String key) {
        Object value = read(key);
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        return true;
    }

    private boolean truthy(String key) {
        Object value = read(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return Double.isFinite(number.doubleValue()) && number.doubleValue() != 0.0d;
        }
        if (value instanceof String text) {
            return !text.isBlank() && !"false".equalsIgnoreCase(text.trim());
        }
        return value != null;
    }

    private boolean contains(String key, String fragment) {
        Object value = read(key);
        return value != null && String.valueOf(value).contains(fragment);
    }

    private Object read(String key) {
        try {
            return traceReader.read(key).value();
        } catch (RuntimeException ignored) {
            TraceStore.put("harmony.score.traceRead.failed", Boolean.TRUE);
            TraceStore.put("harmony.score.traceRead.key", key);
            TraceStore.put("harmony.score.traceRead.errorType", ignored.getClass().getSimpleName());
            return null;
        }
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(100.0d, value));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
