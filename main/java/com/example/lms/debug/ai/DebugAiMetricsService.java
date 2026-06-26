package com.example.lms.debug.ai;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DebugAiMetricsService {

    private static final int MAX_LIMIT = 500;
    private static final int MAX_HISTORY = 48;
    private static final long DEFAULT_WINDOW_MS = 60_000L;
    private static final long MAX_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final long ANOMALY_ERROR_DELTA_THRESHOLD = 2L;
    private static final long ANOMALY_WARN_DELTA_THRESHOLD = 4L;
    private static final double ANOMALY_SCORE_THRESHOLD = 0.70d;
    private static final double ANOMALY_POWER = 3.0d;

    private final DebugEventStore store;
    private final Deque<DebugAiMetricSnapshot> history = new ConcurrentLinkedDeque<>();

    public DebugAiMetricsService(DebugEventStore store) {
        this.store = store;
    }

    public DebugAiMetricSnapshot snapshot(int limit, long windowMs) {
        DebugAiMetricSnapshot snapshot = buildSnapshot(limit, windowMs);
        recordSnapshot(snapshot);
        return snapshot;
    }

    public void recordSnapshot() {
        recordSnapshot(buildSnapshot(MAX_LIMIT, DEFAULT_WINDOW_MS));
    }

    public List<DebugAiMetricSnapshot> snapshotHistory(int maxEntries) {
        int limit = clamp(maxEntries, 1, MAX_HISTORY);
        List<DebugAiMetricSnapshot> out = new ArrayList<>(limit);
        int i = 0;
        for (DebugAiMetricSnapshot snap : history) {
            if (i++ >= limit) {
                break;
            }
            out.add(snap);
        }
        return List.copyOf(out);
    }

    public Map<String, Object> compactSnapshot(int limit) {
        return compactSnapshot(limit, DEFAULT_WINDOW_MS);
    }

    public Map<String, Object> compactSnapshot(int limit, long windowMs) {
        DebugAiMetricSnapshot snapshot = snapshot(limit, windowMs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", snapshot.schemaVersion());
        out.put("generatedAt", snapshot.generatedAt());
        out.put("windowMs", snapshot.windowMs());
        out.put("totalEvents", snapshot.totalEvents());
        out.put("warnEvents", snapshot.warnEvents());
        out.put("errorEvents", snapshot.errorEvents());
        out.put("usedDebugTools", snapshot.usedDebugTools());
        out.put("planUsage", snapshot.planUsage());
        out.put("tiles", snapshot.tiles());
        out.put("scorecard", snapshot.scorecard());
        out.put("recommendations", snapshot.recommendations());
        return out;
    }

    private DebugAiMetricSnapshot buildSnapshot(int limit, long windowMs) {
        int safeLimit = clamp(limit, 1, MAX_LIMIT);
        long safeWindowMs = clampWindow(windowMs);
        long nowMs = System.currentTimeMillis();
        long cutoffMs = nowMs - safeWindowMs;

        List<DebugAiRawSlot> slots = new ArrayList<>();
        if (store != null) {
            for (DebugEvent event : store.list(safeLimit)) {
                if (event == null || event.tsMs() < cutoffMs) {
                    continue;
                }
                slots.add(slotFromEvent(event));
            }
        }
        slotFromTraceStore(nowMs).ifPresent(slots::add);

        Map<String, Long> probeCounts = counts(slots, DebugAiRawSlot::probe);
        Map<String, Long> layerCounts = counts(slots, DebugAiRawSlot::layer);
        Map<String, Long> failureClassCounts = counts(slots, DebugAiRawSlot::failureClass);
        List<DebugAiRawTile> tiles = tiles(slots);
        long warnEvents = slots.stream().filter(s -> "WARN".equalsIgnoreCase(s.severity())).count();
        long errorEvents = slots.stream().filter(s -> "ERROR".equalsIgnoreCase(s.severity())).count();

        return new DebugAiMetricSnapshot(
                1,
                Instant.ofEpochMilli(nowMs),
                safeWindowMs,
                slots.size(),
                warnEvents,
                errorEvents,
                probeCounts,
                layerCounts,
                failureClassCounts,
                fingerprintHotspots(slots),
                usage(slots, DebugAiRawSlot::toolId, "toolId"),
                usage(slots, DebugAiRawSlot::planId, "planId"),
                tiles,
                scorecard(slots, tiles, warnEvents, errorEvents),
                recommendations(tiles, failureClassCounts, slots));
    }

    private void recordSnapshot(DebugAiMetricSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        history.addFirst(snapshot);
        while (history.size() > MAX_HISTORY) {
            history.pollLast();
        }
    }

    private DebugAiRawSlot slotFromEvent(DebugEvent event) {
        Map<String, Object> data = event.data() == null ? Map.of() : event.data();
        String probe = event.probe() == null ? "GENERIC" : event.probe().name();
        String layer = firstNonBlank(
                label(data.get("layer")),
                layerFromProbe(event.probe()),
                "spring.context");
        String failureClass = firstNonBlank(
                label(data.get("failureClass")),
                label(data.get("failure_class")),
                label(data.get("disabledReason")),
                label(data.get("reason")),
                event.error() == null ? null : label(event.error().type()),
                queryTransformerFailureClass(data, event.probe()),
                event.level() == DebugEventLevel.ERROR ? "error" : null,
                event.level() == DebugEventLevel.WARN ? "warning" : "observed");
        return new DebugAiRawSlot(
                safeMessage(event.id(), 96),
                event.tsMs(),
                probe,
                safeMessage(layer, 96),
                safeMessage(failureClass, 96),
                hash(event.fingerprint()),
                hashAlready(event.sid()),
                hashAlready(event.traceId()),
                hashAlready(event.requestId()),
                safeMessage(event.where(), 160),
                firstLong(data, "latencyMs", "tookMs", "durationMs", "timeoutMs"),
                safeId(data, "toolId", "debug.toolId", "tool"),
                safeId(data, "planId", "debug.planId", "plan"),
                hash(firstNonBlank(string(data.get("verificationCommand")), string(data.get("command")))),
                safeMessage(firstNonBlank(label(data.get("result")), label(data.get("status")),
                        event.level() == null ? null : event.level().name()), 80),
                event.level() == null ? "INFO" : event.level().name(),
                event.probe() == DebugProbeType.QUERY_TRANSFORMER ? firstLong(data, "subModelCount") : 0L,
                event.probe() == DebugProbeType.QUERY_TRANSFORMER ? firstLong(data, "branchTitleCount") : 0L,
                event.probe() == DebugProbeType.QUERY_TRANSFORMER ? branchTitleHashCount(data) : 0L,
                event.probe() == DebugProbeType.QUERY_TRANSFORMER ? firstLong(data, "branchAxisCount") : 0L,
                event.probe() == DebugProbeType.QUERY_TRANSFORMER ? firstLong(data, "paddedCount") : 0L);
    }

    private java.util.Optional<DebugAiRawSlot> slotFromTraceStore(long nowMs) {
        Map<String, Object> trace = TraceStore.getAll();
        String toolId = safeId(trace, "debug.toolId", "toolId", "tool.id");
        String planId = safeId(trace, "debug.planId", "planId", "plan.id");
        String failureClass = firstNonBlank(
                label(trace.get("debug.failureClass")),
                label(trace.get("failureClass")),
                label(trace.get("disabledReason")),
                label(trace.get("reason")));
        String verification = firstNonBlank(string(trace.get("debug.verificationCommand")),
                string(trace.get("verificationCommand")),
                string(trace.get("command")));
        if (toolId == null && planId == null && failureClass == null && verification == null) {
            return java.util.Optional.empty();
        }
        String layer = firstNonBlank(label(trace.get("debug.layer")), label(trace.get("layer")), "agent.tool");
        return java.util.Optional.of(new DebugAiRawSlot(
                "trace-current",
                nowMs,
                "TRACE_STORE",
                safeMessage(layer, 96),
                safeMessage(firstNonBlank(failureClass, "observed"), 96),
                hash(firstNonBlank(failureClass, toolId, planId, layer)),
                hashAlready(string(trace.get("sid"))),
                hashAlready(firstNonBlank(string(trace.get("traceId")), string(trace.get("trace.id")))),
                hashAlready(firstNonBlank(string(trace.get("requestId")), string(trace.get("rid")))),
                "TraceStore",
                firstLong(trace, "latencyMs", "tookMs", "durationMs", "timeoutMs"),
                toolId,
                planId,
                hash(verification),
                safeMessage(firstNonBlank(label(trace.get("debug.result")), label(trace.get("result")), "observed"), 80),
                "INFO",
                0L,
                0L,
                0L,
                0L,
                0L));
    }

    private List<DebugAiRawTile> tiles(List<DebugAiRawSlot> slots) {
        List<DebugAiRawTile> out = new ArrayList<>();
        for (DebugAiTileType type : DebugAiTileType.values()) {
            List<DebugAiRawSlot> matching = slots.stream()
                    .filter(slot -> tileFor(slot) == type)
                    .toList();
            long warn = matching.stream().filter(s -> "WARN".equalsIgnoreCase(s.severity())).count();
            long error = matching.stream().filter(s -> "ERROR".equalsIgnoreCase(s.severity())).count();
            long lastTs = matching.stream().mapToLong(DebugAiRawSlot::tsMs).max().orElse(0L);
            out.add(new DebugAiRawTile(
                    type.ordinal(),
                    type.name(),
                    matching.size(),
                    warn,
                    error,
                    topValue(matching, DebugAiRawSlot::failureClass),
                    topValue(matching, DebugAiRawSlot::fingerprintHash),
                    lastTs,
                    error > 0 ? "error" : warn > 0 ? "warn" : matching.isEmpty() ? "idle" : "observed"));
        }
        return out;
    }

    private DebugAiTileType tileFor(DebugAiRawSlot slot) {
        String joined = (slot.probe() + " " + slot.layer() + " " + slot.failureClass() + " " + slot.toolId())
                .toLowerCase(Locale.ROOT);
        if (slot.verificationCommandHash() != null || joined.contains("gradle") || joined.contains("compile")
                || joined.contains("build") || joined.contains("verification")) {
            return DebugAiTileType.VERIFICATION_BUILD;
        }
        if (joined.contains("external_evidence") || joined.contains("external.evidence")
                || joined.contains("external-evidence")) {
            return DebugAiTileType.EXTERNAL_EVIDENCE;
        }
        if (slot.toolId() != null || joined.contains("agent.tool") || joined.contains("agent.report")
                || joined.contains("tool")) {
            return DebugAiTileType.AGENT_TOOL_USAGE;
        }
        if (joined.contains("query_transformer") || joined.contains("querytransformer")) {
            return DebugAiTileType.QUERY_TRANSFORMER;
        }
        if (joined.contains("web_search") || joined.contains("naver") || joined.contains("brave")
                || joined.contains("serpapi") || joined.contains("tavily") || joined.contains("web.search")) {
            return DebugAiTileType.WEB_SEARCH;
        }
        if (joined.contains("vector") || joined.contains("embedding")) {
            return DebugAiTileType.VECTOR_RETRIEVAL;
        }
        if (joined.contains("cancel") || joined.contains("shield") || joined.contains("interrupt")
                || joined.contains("nightmare") || joined.contains("breaker")) {
            return DebugAiTileType.SPRING_CONTEXT;
        }
        if (joined.contains("kg") || joined.contains("graph")) {
            return DebugAiTileType.KG_GRAPH;
        }
        if (joined.contains("model_guard") || joined.contains("modelguard") || joined.contains("llm")) {
            return DebugAiTileType.LLM_MODEL_GUARD;
        }
        if (joined.contains("evidence") || joined.contains("prompt")) {
            return DebugAiTileType.EVIDENCE_OUTPUT;
        }
        if (joined.contains("image_job") || joined.contains("image.job") || joined.contains("imagejob")) {
            return DebugAiTileType.IMAGE_JOB;
        }
        return DebugAiTileType.SPRING_CONTEXT;
    }

    private List<Map<String, Object>> usage(List<DebugAiRawSlot> slots,
                                            Function<DebugAiRawSlot, String> extractor,
                                            String keyName) {
        Map<String, List<DebugAiRawSlot>> grouped = slots.stream()
                .filter(slot -> extractor.apply(slot) != null)
                .collect(Collectors.groupingBy(extractor, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<DebugAiRawSlot>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(keyName, entry.getKey());
            row.put("count", entry.getValue().size());
            row.put("lastTsMs", entry.getValue().stream().mapToLong(DebugAiRawSlot::tsMs).max().orElse(0L));
            row.put("resultCounts", counts(entry.getValue(), DebugAiRawSlot::result));
            out.add(row);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> number(m.get("count"))).reversed());
        return out;
    }

    private List<Map<String, Object>> fingerprintHotspots(List<DebugAiRawSlot> slots) {
        Map<String, List<DebugAiRawSlot>> grouped = slots.stream()
                .filter(slot -> slot.fingerprintHash() != null)
                .collect(Collectors.groupingBy(DebugAiRawSlot::fingerprintHash, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<DebugAiRawSlot>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fingerprintHash", entry.getKey());
            row.put("count", entry.getValue().size());
            row.put("topFailureClass", topValue(entry.getValue(), DebugAiRawSlot::failureClass));
            row.put("lastTsMs", entry.getValue().stream().mapToLong(DebugAiRawSlot::tsMs).max().orElse(0L));
            out.add(row);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> number(m.get("count"))).reversed());
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    private Map<String, Object> scorecard(List<DebugAiRawSlot> slots,
                                          List<DebugAiRawTile> tiles,
                                          long warnEvents,
                                          long errorEvents) {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = Math.max(1, slots.size());
        DebugAiMetricSnapshot previous = history.peekFirst();
        long warnDelta = previous == null ? 0L : warnEvents - previous.warnEvents();
        long errorDelta = previous == null ? 0L : errorEvents - previous.errorEvents();
        DebugAiRawTile hotTile = tiles.stream()
                .max(Comparator.comparingLong(DebugAiRawTile::eventCount))
                .orElse(null);
        String hotTileName = hotTile == null ? "SPRING_CONTEXT" : hotTile.tileName();
        String anomalyFailureClass = hotTile == null ? null : hotTile.topFailureClass();
        double anomalyScore = anomalyScore(total, warnEvents, errorEvents, warnDelta, errorDelta, hotTile);
        String anomalyReason = anomalyReason(previous, warnDelta, errorDelta, hotTile, anomalyScore);
        boolean anomalyTriggered = previous != null && !"observe".equals(anomalyReason);
        out.put("warnRatio", warnEvents / (double) total);
        out.put("errorRatio", errorEvents / (double) total);
        out.put("warnTrend", trendLabel(previous, warnDelta));
        out.put("errorTrend", trendLabel(previous, errorDelta));
        out.put("warnDelta", warnDelta);
        out.put("errorDelta", errorDelta);
        out.put("toolUsageCount", slots.stream().filter(s -> s.toolId() != null).count());
        out.put("verificationUsageCount", slots.stream().filter(s -> s.verificationCommandHash() != null).count());
        out.put("queryRewriteSubModelCount", slots.stream()
                .mapToLong(DebugAiRawSlot::queryRewriteSubModelCount)
                .sum());
        out.put("queryRewriteBranchTitleCount", slots.stream()
                .mapToLong(DebugAiRawSlot::queryRewriteBranchTitleCount)
                .sum());
        out.put("queryRewriteBranchTitleHashCount", slots.stream()
                .mapToLong(DebugAiRawSlot::queryRewriteBranchTitleHashCount)
                .sum());
        out.put("queryRewriteBranchAxisCount", slots.stream()
                .mapToLong(DebugAiRawSlot::queryRewriteBranchAxisCount)
                .sum());
        out.put("queryRewritePaddedCount", slots.stream()
                .mapToLong(DebugAiRawSlot::queryRewritePaddedCount)
                .sum());
        out.put("hotTile", hotTileName);
        out.put("historySize", history.size());
        out.put("anomalyTriggered", anomalyTriggered);
        out.put("anomalyReason", anomalyReason);
        out.put("anomalyScore", roundScore(anomalyScore));
        out.put("anomalyTile", hotTileName);
        out.put("anomalyFailureClass", anomalyFailureClass);
        traceAnomaly(anomalyTriggered, anomalyReason, hotTileName, anomalyFailureClass,
                roundScore(anomalyScore), history.size());
        return out;
    }

    private static double anomalyScore(long total,
                                       long warnEvents,
                                       long errorEvents,
                                       long warnDelta,
                                       long errorDelta,
                                       DebugAiRawTile hotTile) {
        double severityPressure = clip01((errorEvents + warnEvents * 0.5d) / Math.max(1d, total));
        double deltaPressure = clip01((Math.max(0L, errorDelta) + Math.max(0L, warnDelta) * 0.5d) / 3.0d);
        double hotTilePressure = 0.0d;
        if (hotTile != null && hotTile.eventCount() > 0L) {
            hotTilePressure = clip01((hotTile.errorCount() + hotTile.warnCount() * 0.5d)
                    / (double) hotTile.eventCount());
        }
        return weightedPowerMean(
                new double[]{severityPressure, deltaPressure, hotTilePressure},
                new double[]{0.45d, 0.35d, 0.20d});
    }

    private static String anomalyReason(DebugAiMetricSnapshot previous,
                                        long warnDelta,
                                        long errorDelta,
                                        DebugAiRawTile hotTile,
                                        double anomalyScore) {
        if (previous == null) {
            return "observe";
        }
        if (errorDelta >= ANOMALY_ERROR_DELTA_THRESHOLD) {
            return "error_delta_threshold";
        }
        if (warnDelta >= ANOMALY_WARN_DELTA_THRESHOLD) {
            return "warn_delta_threshold";
        }
        if (hotTile != null && hotTile.errorCount() >= 3L) {
            return "hot_tile_spike";
        }
        if (anomalyScore >= ANOMALY_SCORE_THRESHOLD) {
            return "weighted_signal_pressure";
        }
        return "observe";
    }

    private static double weightedPowerMean(double[] values, double[] weights) {
        double numerator = 0.0d;
        double denominator = 0.0d;
        int limit = Math.min(values.length, weights.length);
        for (int i = 0; i < limit; i++) {
            double weight = Math.max(0.0d, weights[i]);
            numerator += weight * Math.pow(clip01(values[i]), ANOMALY_POWER);
            denominator += weight;
        }
        if (denominator <= 0.0d) {
            return 0.0d;
        }
        return clip01(Math.pow(numerator / denominator, 1.0d / ANOMALY_POWER));
    }

    private static double clip01(double value) {
        if (Double.isNaN(value) || value <= 0.0d) {
            return 0.0d;
        }
        return Math.min(1.0d, value);
    }

    private static double roundScore(double value) {
        return Math.round(clip01(value) * 1000.0d) / 1000.0d;
    }

    private static void traceAnomaly(boolean triggered,
                                     String reason,
                                     String tile,
                                     String failureClass,
                                     double score,
                                     int historySize) {
        try {
            TraceStore.put("debug.ai.metrics.anomaly.triggered", triggered);
            TraceStore.put("debug.ai.metrics.anomaly.reason",
                    SafeRedactor.traceLabelOrFallback(reason, "observe"));
            TraceStore.put("debug.ai.metrics.anomaly.tile",
                    SafeRedactor.traceLabelOrFallback(tile, "unknown"));
            TraceStore.put("debug.ai.metrics.anomaly.score", score);
            TraceStore.put("debug.ai.metrics.anomaly.history.size", Math.max(0, historySize));
            if (failureClass != null) {
                TraceStore.put("debug.ai.metrics.anomaly.failureClass",
                        SafeRedactor.traceLabelOrFallback(failureClass, "unknown"));
            }
        } catch (Throwable ignore) {
            traceSuppressed("debugAiMetrics.anomalyTrace", ignore);
        }
    }

    private static String trendLabel(DebugAiMetricSnapshot previous, long delta) {
        if (previous == null) {
            return "none";
        }
        if (delta > 0L) {
            return "up";
        }
        if (delta < 0L) {
            return "down";
        }
        return "flat";
    }

    private List<String> recommendations(List<DebugAiRawTile> tiles,
                                         Map<String, Long> failureClassCounts,
                                         List<DebugAiRawSlot> slots) {
        List<String> out = new ArrayList<>();
        if (slots.isEmpty()) {
            out.add("observe_debug_events");
            return out;
        }
        tiles.stream()
                .filter(t -> t.errorCount() > 0)
                .findFirst()
                .ifPresent(t -> out.add("review_error_tile:" + t.tileName()));
        if (failureClassCounts.containsKey("timeout") || failureClassCounts.containsKey("rate-limit")) {
            out.add("inspect_provider_failsoft_ladder");
        }
        if (slots.stream().noneMatch(s -> s.toolId() != null)) {
            out.add("record_debug_tool_usage");
        }
        if (out.isEmpty()) {
            out.add("continue_observing");
        }
        return List.copyOf(out);
    }

    private static Map<String, Long> counts(List<DebugAiRawSlot> slots, Function<DebugAiRawSlot, String> extractor) {
        Map<String, Long> out = slots.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return sortedCounts(out);
    }

    private static Map<String, Long> sortedCounts(Map<String, Long> in) {
        return in.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private static String topValue(List<DebugAiRawSlot> slots, Function<DebugAiRawSlot, String> extractor) {
        return counts(slots, extractor).entrySet().stream()
                .findFirst()
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static String layerFromProbe(DebugProbeType probe) {
        if (probe == null) {
            return null;
        }
        return switch (probe) {
            case HTTP -> "http";
            case CONTEXT_PROPAGATION -> "context.propagation";
            case GUARD_CONTEXT -> "guard.context";
            case RULE_BREAK -> "guard.ruleBreak";
            case FAULT_MASK -> "failsoft.faultMask";
            case QUERY_TRANSFORMER -> "query.transformer";
            case NIGHTMARE_BREAKER -> "breaker";
            case EXECUTOR -> "executor";
            case REACTOR -> "reactor";
            case AUTOLEARN -> "learning.autolearn";
            case WEB_SEARCH, NAVER_SEARCH -> "web.search";
            case EMBEDDING -> "vector.retrieval";
            case MODEL_GUARD -> "llm.modelGuard";
            case PROMPT -> "evidence.output";
            case ORCHESTRATION -> "agent.tool";
            case EXTERNAL_EVIDENCE -> "external.evidence";
            case GENERIC -> "spring.context";
            default -> probe.name().toLowerCase(Locale.ROOT).replace('_', '.');
        };
    }

    private static String queryTransformerFailureClass(Map<String, Object> data, DebugProbeType probe) {
        if (probe != DebugProbeType.QUERY_TRANSFORMER || data == null) {
            return null;
        }
        String stage = label(data.get("stage"));
        if (stage == null || stage.isBlank()) {
            return null;
        }
        if ("query_rewrite".equals(stage)) {
            long superCount = firstLong(data, "superCount", "branchCount");
            return superCount > 0L ? "query_rewrite.super_tokens" : "query_rewrite";
        }
        return stage;
    }

    private static String safeId(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            String label = label(data.get(key));
            if (label != null && label.matches("[A-Za-z0-9_.:-]{1,120}")) {
                return label;
            }
        }
        return null;
    }

    private static String label(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            Object hash = firstPresent(map.get("hash12"), map.get("hash"), map.get("fingerprintHash"));
            if (hash != null) {
                String h = String.valueOf(hash).trim();
                return h.startsWith("hash:") ? h : "hash:" + h;
            }
            Object host = map.get("host");
            if (host != null) {
                return SafeRedactor.traceLabelOrFallback(host, "host");
            }
            return map.containsKey("present") ? "present" : "map";
        }
        return SafeRedactor.traceLabelOrFallback(String.valueOf(value), "present");
    }

    private static long firstLong(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            Long parsed = longValue(value);
            if (parsed != null) {
                return Math.max(0L, parsed);
            }
        }
        return 0L;
    }

    private static long branchTitleHashCount(Map<String, Object> data) {
        long explicit = firstLong(data, "branchTitleHashCount");
        if (explicit > 0L) {
            return explicit;
        }
        Object value = data.get("branchTitleHashes");
        if (value instanceof Iterable<?> iterable) {
            long count = 0L;
            for (Object item : iterable) {
                if (isHash12(item)) {
                    count++;
                }
            }
            return count;
        }
        return isHash12(value) ? 1L : 0L;
    }

    private static boolean isHash12(Object value) {
        if (value == null) {
            return false;
        }
        return String.valueOf(value).trim().matches("[a-f0-9]{12}");
    }

    private static Long longValue(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignore) {
                traceSuppressed("debugAiMetrics.longValue", ignore);
                return null;
            }
        }
        return null;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("debug.ai.metrics.suppressed.stage", safeStage);
        TraceStore.put("debug.ai.metrics.suppressed.errorType", safeErrorType);
        TraceStore.put("debug.ai.metrics.suppressed." + safeStage, true);
        TraceStore.put("debug.ai.metrics.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : failure.getClass().getSimpleName();
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private static String hash(String value) {
        String h = SafeRedactor.hashValue(value);
        return h == null || h.isBlank() ? null : h;
    }

    private static String hashAlready(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.startsWith("hash:") ? value : hash(value);
    }

    private static String safeMessage(String value, int max) {
        return value == null ? null : SafeRedactor.safeMessage(value, max);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Object firstPresent(Object first, Object second, Object third) {
        return first != null ? first : second != null ? second : third;
    }

    private static Object firstPresent(Object first, Object second) {
        return first != null ? first : second;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampWindow(long value) {
        if (value <= 0L) {
            return DEFAULT_WINDOW_MS;
        }
        return Math.max(1_000L, Math.min(MAX_WINDOW_MS, value));
    }
}
