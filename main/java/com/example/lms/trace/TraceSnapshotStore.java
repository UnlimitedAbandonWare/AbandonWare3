package com.example.lms.trace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;

/**
 * In-memory ring buffer for request/task trace snapshots.
 *
 * <p>Why this exists:</p>
 * <ul>
 *   <li>{@link TraceStore} is ThreadLocal and cleared at the end of the request.</li>
 *   <li>When a bug happens (DI missing, stage handoff skipped, embedding failover, flush error),
 *       we want to retain a compact snapshot that can be inspected via a web endpoint.</li>
 * </ul>
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li>Fail-soft: never block the request.</li>
 *   <li>Bounded memory: keep only the last N snapshots.</li>
 *   <li>Safe serialization: sanitize values to JSON-friendly primitives/strings.</li>
 * </ul>
 */
@Component
public class TraceSnapshotStore {

    private static final Logger LOG = LoggerFactory.getLogger("TRACE_SNAPSHOT");

    @Value("${trace.snapshot.enabled:true}")
    private boolean enabled;

    @Value("${trace.snapshot.max-size:200}")
    private int maxSize;

    @Value("${trace.snapshot.max-value-len:2000}")
    private int maxValueLen;

    @Value("${trace.snapshot.max-entries:800}")
    private int maxEntries;

    // ---------------------------------------------------------------------
    // Allow/Deny filters (reason + key combinations)
    // ---------------------------------------------------------------------

    /** Comma-separated patterns (supports '*' wildcards). If set, only matching reasons are captured. */
    @Value("${trace.snapshot.allow-reasons:}")
    private String allowReasonsCsv;

    /** Comma-separated patterns (supports '*' wildcards). If set, matching reasons are skipped. */
    @Value("${trace.snapshot.deny-reasons:}")
    private String denyReasonsCsv;

    /** Comma-separated key patterns (supports '*' wildcards). If set, a snapshot must include matching keys. */
    @Value("${trace.snapshot.allow-keys:}")
    private String allowKeysCsv;

    /** Whether allow-keys uses ANY or ALL semantics. */
    @Value("${trace.snapshot.allow-keys-mode:any}")
    private String allowKeysMode;

    /** Comma-separated key patterns (supports '*' wildcards). If set, snapshots containing matching keys are skipped. */
    @Value("${trace.snapshot.deny-keys:}")
    private String denyKeysCsv;

    // ---------------------------------------------------------------------
    // Capture tuning knobs
    // ---------------------------------------------------------------------

    /** Sampling ratio for "non-critical" snapshots (0.0..1.0). Critical snapshots (exceptions/status>=min) bypass sampling. */
    @Value("${trace.snapshot.capture.sample:1.0}")
    private double captureSample;

    /** Per-trace capture: minimum interval between snapshots (ms). */
    @Value("${trace.snapshot.capture.min-interval-ms:200}")
    private long minIntervalMs;

    /** Per-trace capture: maximum snapshots per trace within {@link #budgetWindowMs}. */
    @Value("${trace.snapshot.capture.max-per-trace:8}")
    private int maxPerTrace;

    /** Capture budget window used by {@link #maxPerTrace}. */
    @Value("${trace.snapshot.capture.budget.window-ms:600000}")
    private long budgetWindowMs;

    /** If status >= this threshold, capture regardless of sampling. */
    @Value("${trace.snapshot.capture.http.status-min:400}")
    private int httpStatusMin;

    @Value("${trace.snapshot.capture.http.on-debug:true}")
    private boolean captureHttpOnDebug;

    @Value("${trace.snapshot.capture.http.on-ml:true}")
    private boolean captureHttpOnMl;

    @Value("${trace.snapshot.capture.http.on-orch:true}")
    private boolean captureHttpOnOrch;

    @Value("${trace.snapshot.capture.http.on-exception:true}")
    private boolean captureHttpOnException;

    // ---------------------------------------------------------------------
    // Snapshot HTML (optional)
    // ---------------------------------------------------------------------

    @Value("${trace.snapshot.html.enabled:true}")
    private boolean htmlEnabled;

    @Value("${trace.snapshot.html.max-len:60000}")
    private int htmlMaxLen;

    private final ObjectProvider<com.example.lms.service.trace.TraceHtmlBuilder> htmlBuilderProvider;

    /** Per-trace capture budget (helps prevent over-capture for loops/background tasks). */
    private final java.util.concurrent.ConcurrentHashMap<String, CaptureBudget> budgets = new java.util.concurrent.ConcurrentHashMap<>();

    private final Object lock = new Object();
    private final Deque<TraceSnapshot> ring = new ArrayDeque<>();

    public TraceSnapshotStore(ObjectProvider<com.example.lms.service.trace.TraceHtmlBuilder> htmlBuilderProvider) {
        this.htmlBuilderProvider = htmlBuilderProvider;
    }

    private static final class CaptureBudget {
        volatile long firstAtMs;
        volatile long lastAtMs;
        volatile int count;

        CaptureBudget(long nowMs) {
            this.firstAtMs = nowMs;
            this.lastAtMs = nowMs;
            this.count = 0;
        }
    }

    public record TraceSnapshot(
            String id,
            long tsEpochMs,
            String tsIso,
            String sid,
            String sessionId,
            String traceId,
            String requestId,
            String reason,
            String method,
            String path,
            Integer status,
            String error,
            boolean hasMlBreadcrumbs,
            int traceEntryCount,
            Map<String, String> mdc,
            Map<String, Object> trace,
            Map<String, Object> orchestration,
            @JsonIgnore String html,
            boolean htmlTruncated
    ) {
    }

    /** Capture current MDC + TraceStore into the ring buffer. Returns snapshot id or null. */
    public String captureCurrent(
            String reason,
            String method,
            String path,
            Integer status,
            Throwable error
    ) {
        return captureInternal(reason, method, path, status, error, null, null);
    }

    /** Capture a snapshot with an explicit trace map and/or pre-rendered HTML. */
    public String captureCustom(
            String reason,
            String method,
            String path,
            Integer status,
            Throwable error,
            Map<String, Object> traceOverride,
            String htmlOverride
    ) {
        return captureInternal(reason, method, path, status, error, traceOverride, htmlOverride);
    }

    private String captureInternal(
            String reason,
            String method,
            String path,
            Integer status,
            Throwable error,
            Map<String, Object> traceOverride,
            String htmlOverride
    ) {
        if (!enabled) {
            traceCaptureSkipped(reason, "disabled");
            return null;
        }
        try {
            long ts = System.currentTimeMillis();
            String tsIso = Instant.ofEpochMilli(ts).toString();

            Map<String, String> rawMdc = safeMdc();
            Map<String, Object> rawTrace = (traceOverride == null) ? safeTrace() : traceOverride;

            // Allow/Deny filters (reason + key combos)
            if (!passesFilters(reason, rawTrace)) {
                traceCaptureSkipped(reason, "filter_rejected");
                return null;
            }

            boolean dbgSearch = isDbgSearch(rawMdc, rawTrace);
            boolean hasMl = hasPrefix(rawTrace, "ml.");
            boolean hasOrch = hasPrefix(rawTrace, "orch.");
            boolean hasException = error != null;
            boolean statusTrigger = status != null && status >= httpStatusMin;

            // Decide whether to capture this snapshot.
            if (!shouldCapture(reason, dbgSearch, hasMl, hasOrch, hasException, statusTrigger)) {
                traceCaptureSkipped(reason, "capture_policy_not_triggered");
                return null;
            }

            // Sampling (non-critical only)
            boolean critical = hasException || statusTrigger || !"http_request".equalsIgnoreCase(safe(reason));
            if (!critical && captureSample < 1.0d) {
                double r = Math.random();
                if (r > Math.max(0.0d, Math.min(1.0d, captureSample))) {
                    traceCaptureSkipped(reason, "sampled_out");
                    return null;
                }
            }

            String rawSid = firstNonBlank(rawMdc.get("sid"), rawMdc.get("sessionId"));
            String rawTraceId = firstNonBlank(
                    rawMdc.get("traceId"),
                    rawMdc.get("trace"),
                    rawMdc.get("x-request-id"),
                    firstString(rawTrace == null ? null : rawTrace.get("trace.id")),
                    firstString(rawTrace == null ? null : rawTrace.get("traceId"))
            );
            String rawRequestId = firstNonBlank(rawMdc.get("x-request-id"), rawTraceId);

            // Rate limit per trace id (helps prevent accidental hot loops).
            if (!consumeBudget(rawTraceId, ts)) {
                traceCaptureSkipped(reason, "budget_exhausted");
                return null;
            }

            Map<String, String> mdc = sanitizeMdc(rawMdc);
            Map<String, Object> trace = sanitizeTrace(rawTrace);
            Map<String, Object> orchestration = buildOrchestration(trace);

            String sid = SafeRedactor.hashValue(rawSid);
            String traceId = SafeRedactor.hashValue(rawTraceId);
            String requestId = SafeRedactor.hashValue(rawRequestId);
            String err = (error == null) ? null : String.valueOf(SafeRedactor.diagnosticValue("error", String.valueOf(error), 600));
            String snapshotReason = SafeRedactor.traceLabelOrFallback(reason, "");
            if (snapshotReason == null) snapshotReason = "";
            String snapshotPath = safeRequestPath(path);

            String id = UUID.randomUUID().toString();

            // Optional: store an HTML diagnostics view (useful for web-based inspection).
            String html = null;
            boolean htmlTruncated = false;
            if (htmlOverride != null && !htmlOverride.isBlank()) {
                String trimmedOverride = htmlOverride.trim();
                boolean trustedGeneratedHtml =
                        (trimmedOverride.startsWith("<!doctype html><html data-trace-redacted=\"1\"")
                                || trimmedOverride.startsWith("<details data-trace-redacted=\"1\" class=\"search-trace "))
                                && "splitPanel".equals(firstString(rawTrace == null ? null : rawTrace.get("ui.traceHtml.kind")))
                                && intValue(rawTrace == null ? null : rawTrace.get("ui.traceHtml.length")) == htmlOverride.length();
                String overrideHtml = trustedGeneratedHtml
                        ? htmlOverride
                        : "<pre class=\"mono\">"
                                + htmlEscape(String.valueOf(SafeRedactor.diagnosticValue("query", htmlOverride, 2000)))
                                + "</pre>";
                html = wrapHtmlIfNeeded(overrideHtml, id, tsIso, sid, traceId, requestId, snapshotReason, safe(method), snapshotPath, status, err);
            } else if (htmlEnabled) {
                try {
                    html = buildHtmlSnapshot(id, tsIso, sid, traceId, requestId, snapshotReason, safe(method), snapshotPath, status, err, mdc, trace);
                } catch (Throwable ignore) {
                    TraceStore.put("trace.snapshot.suppressed.htmlBuild", true);
                    TraceStore.put("trace.snapshot.suppressed.htmlBuild.errorType",
                            SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
                    html = null;
                }
            }
            if (html != null && html.length() > Math.max(1024, htmlMaxLen)) {
                html = html.substring(0, Math.max(1024, htmlMaxLen)) + "\n<!-- truncated -->";
                htmlTruncated = true;
            }

            TraceSnapshot snap = new TraceSnapshot(
                    id,
                    ts,
                    tsIso,
                    sid,
                    sid,
                    traceId,
                    requestId,
                    snapshotReason,
                    safe(method),
                    snapshotPath,
                    status,
                    err,
                    (hasMl || hasOrch),
                    rawTrace == null ? 0 : rawTrace.size(),
                    mdc,
                    trace,
                    orchestration,
                    html,
                    htmlTruncated
            );

            synchronized (lock) {
                ring.addFirst(snap);
                while (ring.size() > Math.max(1, maxSize)) {
                    ring.removeLast();
                }
            }

            // Minimal console breadcrumb for correlation.
            if (LOG.isInfoEnabled()) {
                LOG.info("[TRACE_SNAPSHOT] id={} sidHash={} traceHash={} reqHash={} status={} reason={} ml={} orch={} pathHash={} pathLength={}{}",
                        id,
                        safe(sid),
                        safe(traceId),
                        safe(requestId),
                        status,
                        snapshotReason,
                        hasMl,
                        hasOrch,
                        SafeRedactor.hashValue(path),
                        path == null ? 0 : path.length(),
                        (err == null ? "" : (" err=" + err)));
            }

            return id;
        } catch (Throwable t) {
            // Fail-soft.
            traceCaptureFailed(t);
            try {
                LOG.debug("[TRACE_SNAPSHOT] capture failed errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(t)), messageLength(t));
            } catch (Throwable ignore) {
                logSuppressed("capture.failure.log", ignore);
            }
            return null;
        }
    }

    private static void traceCaptureSkipped(String reason, String skipReason) {
        try {
            TraceStore.put("trace.snapshot.capture.skipped", true);
            TraceStore.put("trace.snapshot.capture.skipReason",
                    SafeRedactor.traceLabelOrFallback(skipReason, "unknown"));
            TraceStore.put("trace.snapshot.capture.reason",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (Throwable ignore) {
            logSuppressed("capture.skipTrace", ignore);
        }
    }

    private static void traceCaptureFailed(Throwable error) {
        try {
            TraceStore.put("trace.snapshot.capture.failed", true);
            TraceStore.put("trace.snapshot.capture.errorType",
                    SafeRedactor.traceLabelOrFallback(
                            error == null ? null : error.getClass().getSimpleName(),
                            "unknown"));
            TraceStore.put("trace.snapshot.capture.errorHash", SafeRedactor.hashValue(messageOf(error)));
        } catch (Throwable ignore) {
            logSuppressed("capture.failureTrace", ignore);
        }
    }

    /** Return newest-first snapshot summaries. */
    public List<Map<String, Object>> listSummaries(int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 50 : limit, maxSize));
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (lock) {
            int i = 0;
            for (TraceSnapshot s : ring) {
                if (s == null) continue;
                out.add(summary(s));
                if (++i >= lim) break;
            }
        }
        return out;
    }

    public Optional<TraceSnapshot> get(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        synchronized (lock) {
            for (TraceSnapshot s : ring) {
                if (s != null && id.equals(s.id())) {
                    return Optional.of(s);
                }
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private Map<String, Object> summary(TraceSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("ts", s.tsIso());
        m.put("sid", s.sid());
        m.put("traceId", s.traceId());
        m.put("requestId", s.requestId());
        m.put("reason", s.reason());
        m.put("method", s.method());
        m.put("path", s.path());
        m.put("pathHash", SafeRedactor.hashValue(s.path()));
        m.put("pathLength", s.path() == null ? 0 : s.path().length());
        m.put("status", s.status());
        m.put("hasMlBreadcrumbs", s.hasMlBreadcrumbs());
        m.put("traceEntryCount", s.traceEntryCount());
        Map<String, Object> orchestration = s.orchestration() == null ? Map.of() : s.orchestration();
        m.put("eventCount", intValue(orchestration.get("eventCount")));
        m.put("controlCount", intValue(orchestration.get("controlCount")));
        Object lastFailureReason = orchestration.get("lastFailureReason");
        if (lastFailureReason != null && !String.valueOf(lastFailureReason).isBlank()) {
            m.put("lastFailureReason", lastFailureReason);
        }
        Object lastControlAction = orchestration.get("lastControlAction");
        if (lastControlAction != null && !String.valueOf(lastControlAction).isBlank()) {
            m.put("lastControlAction", lastControlAction);
        }
        m.put("hasHtml", s.html() != null);
        if (s.htmlTruncated()) {
            m.put("htmlTruncated", true);
        }
        if (s.error() != null) {
            m.put("error", s.error());
        }
        return m;
    }

    private Map<String, Object> buildOrchestration(Map<String, Object> trace) {
        List<Map<String, Object>> events = extractStandardEvents(trace == null ? null : trace.get("orch.events.v1"));
        List<Map<String, Object>> timeline = buildTimeline(events);
        List<Map<String, Object>> controls = buildControls(events);
        Map<String, Object> failureSummary = buildFailureSummary(events);
        Map<String, Object> stageCounts = buildStageCounts(events);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contractVersion", "rag-orch-events.v1");
        out.put("events", events);
        out.put("timeline", timeline);
        out.put("controls", controls);
        out.put("failureSummary", failureSummary);
        out.put("stageCounts", stageCounts);
        out.put("eventCount", events.size());
        out.put("controlCount", controls.size());
        out.put("lastFailureReason", firstString(failureSummary.get("lastReasonCode")));
        out.put("lastControlAction", controls.isEmpty() ? "" : firstString(controls.get(controls.size() - 1).get("action")));
        return out;
    }

    private List<Map<String, Object>> extractStandardEvents(Object rawEvents) {
        if (rawEvents == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (rawEvents instanceof Iterable<?> it) {
            for (Object item : it) {
                Map<String, Object> ev = standardEvent(item);
                if (!ev.isEmpty()) {
                    out.add(ev);
                }
                if (out.size() >= 200) {
                    break;
                }
            }
        } else {
            Map<String, Object> ev = standardEvent(rawEvents);
            if (!ev.isEmpty()) {
                out.add(ev);
            }
        }
        return out;
    }

    private Map<String, Object> standardEvent(Object raw) {
        Map<String, Object> src = toMap(raw);
        if (src.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of(
                "v", "seq", "ts", "traceId", "sessionId", "requestId",
                "kind", "phase", "stage", "step", "component", "status")) {
            out.put(key, eventValue(key, src.get(key)));
        }
        out.put("input", nestedEventMap(src.get("input"), "input",
                List.of("queryHash", "queryLen", "requestedTopK", "planId", "mode")));
        out.put("output", nestedEventMap(src.get("output"), "output",
                List.of("returnedCount", "afterFilterCount", "selectedCount", "stageMs", "sourceDiversity")));
        out.put("failure", nestedEventMap(src.get("failure"), "failure",
                List.of("reasonCode", "failureClass", "exceptionType")));
        out.put("control", nestedEventMap(src.get("control"), "control",
                List.of("action", "applied", "reasonCode", "breadcrumbId")));
        return out;
    }

    private Map<String, Object> nestedEventMap(Object raw, String prefix, List<String> allowedKeys) {
        Map<String, Object> src = toMap(raw);
        if (src.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : allowedKeys) {
            if (src.containsKey(key)) {
                out.put(key, eventValue(prefix + "." + key, src.get(key)));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e == null || e.getKey() == null) {
                    continue;
                }
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private Object eventValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isEventLabelKey(key)) {
            return SafeRedactor.traceLabelOrFallback(value, "unknown");
        }
        return SafeRedactor.diagnosticValue("orch.events.v1." + key, value, maxValueLen);
    }

    private static boolean isEventLabelKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("kind")
                || k.equals("phase")
                || k.equals("stage")
                || k.equals("step")
                || k.equals("component")
                || k.equals("status")
                || k.endsWith(".mode")
                || k.endsWith(".planid")
                || k.endsWith(".reasoncode")
                || k.endsWith(".failureclass")
                || k.endsWith(".exceptiontype")
                || k.endsWith(".action")
                || k.endsWith(".breadcrumbid");
    }

    private List<Map<String, Object>> buildTimeline(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ev : events) {
            Map<String, Object> output = toMap(ev.get("output"));
            Map<String, Object> failure = toMap(ev.get("failure"));
            Map<String, Object> control = toMap(ev.get("control"));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", ev.get("seq"));
            row.put("ts", ev.get("ts"));
            row.put("phase", ev.get("phase"));
            row.put("stage", ev.get("stage"));
            row.put("step", ev.get("step"));
            row.put("component", ev.get("component"));
            row.put("status", ev.get("status"));
            row.put("returnedCount", output.get("returnedCount"));
            row.put("afterFilterCount", output.get("afterFilterCount"));
            row.put("selectedCount", output.get("selectedCount"));
            row.put("stageMs", output.get("stageMs"));
            row.put("reasonCode", firstNonBlank(firstString(failure.get("reasonCode")), firstString(control.get("reasonCode"))));
            row.put("controlAction", control.get("action"));
            row.put("controlApplied", control.get("applied"));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> buildControls(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ev : events) {
            Map<String, Object> control = toMap(ev.get("control"));
            String action = firstString(control.get("action"));
            if (action == null || action.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", ev.get("seq"));
            row.put("ts", ev.get("ts"));
            row.put("stage", ev.get("stage"));
            row.put("step", ev.get("step"));
            row.put("action", action);
            row.put("applied", control.get("applied"));
            row.put("reasonCode", control.get("reasonCode"));
            row.put("breadcrumbId", control.get("breadcrumbId"));
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> buildFailureSummary(List<Map<String, Object>> events) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> byReason = new LinkedHashMap<>();
        String lastReason = "";
        int count = 0;
        if (events != null) {
            for (Map<String, Object> ev : events) {
                Map<String, Object> failure = toMap(ev.get("failure"));
                Map<String, Object> control = toMap(ev.get("control"));
                String reason = firstNonBlank(firstString(failure.get("reasonCode")), firstString(control.get("reasonCode")));
                if (reason == null || reason.isBlank()) {
                    continue;
                }
                count++;
                lastReason = reason;
                byReason.put(reason, byReason.getOrDefault(reason, 0) + 1);
            }
        }
        out.put("count", count);
        out.put("lastReasonCode", lastReason);
        out.put("byReason", byReason);
        return out;
    }

    private Map<String, Object> buildStageCounts(List<Map<String, Object>> events) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (events == null) {
            return out;
        }
        for (Map<String, Object> ev : events) {
            String stage = firstString(ev.get("stage"));
            if (stage == null || stage.isBlank()) {
                stage = "unknown";
            }
            Object existing = out.get(stage);
            int n = existing instanceof Number number ? number.intValue() : intValue(existing);
            out.put(stage, n + 1);
        }
        return out;
    }

    private Map<String, String> safeMdc() {
        Map<String, String> m = MDC.getCopyOfContextMap();
        return (m == null) ? new HashMap<>() : new HashMap<>(m);
    }

    private Map<String, String> sanitizeMdc(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e == null || e.getKey() == null) continue;
            String k = e.getKey();
            out.put(safeTraceKey(k), String.valueOf(SafeRedactor.diagnosticValue("mdc." + k, e.getValue(), maxValueLen)));
            if (++count >= Math.max(50, maxEntries)) break;
        }
        return out;
    }

    private Map<String, Object> safeTrace() {
        try {
            return TraceStore.context();
        } catch (Throwable ignore) {
            TraceStore.put("trace.snapshot.suppressed.context", true);
            TraceStore.put("trace.snapshot.suppressed.context.errorType",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
            return Map.of();
        }
    }

    private Map<String, Object> sanitizeTrace(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e == null) continue;
            String k = e.getKey();
            if (k == null || k.isBlank()) continue;
            Object v = e.getValue();
            String outKey = safeTraceKey(k);
            if ("orch.events.v1".equals(k)) {
                out.put(outKey, extractStandardEvents(v));
            } else if (isTopKKey(k) && v instanceof List<?> list) {
                out.put(outKey, sanitizeTopKList(k, list));
            } else {
                out.put(outKey, SafeRedactor.diagnosticValue(k, v, maxValueLen));
            }
            if (++count >= Math.max(50, maxEntries)) break;
        }
        return out;
    }

    private static String safeTraceKey(String key) {
        return SafeRedactor.traceLabelOrFallback(key, "field");
    }

    private static boolean isTopKKey(String key) {
        if (key == null || key.isBlank()) return false;
        String k = key.toLowerCase(Locale.ROOT);
        if (!k.contains("topk")) return false;
        return k.contains("web") || k.contains("vector") || k.contains("rag") || k.contains("final");
    }

    private Object sanitizeTopKList(String key, List<?> list) {
        if (list == null || list.isEmpty()) return List.of();
        String k = (key == null) ? "" : key.toLowerCase(Locale.ROOT);
        String kind = k.contains("web") ? "web" : (k.contains("vector") ? "vector" : "topk");
        int lim = Math.min(list.size(), 50);
        List<Object> out = new ArrayList<>(lim);
        for (int i = 0; i < lim; i++) {
            Object o = list.get(i);
            if (o instanceof Content c) {
                out.add(toTopKItem(kind, i + 1, c));
            } else {
                out.add(sanitizeValue(o));
            }
        }
        return out;
    }

    private Map<String, Object> toTopKItem(String kind, int rank, Content c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("rank", rank);

        String text = null;
        Object mdObj = null;
        try {
            if (c != null && c.textSegment() != null) {
                text = c.textSegment().text();
                mdObj = c.textSegment().metadata();
            }
        } catch (Throwable ignore) {
            TraceStore.put("trace.snapshot.suppressed.contentText", true);
            TraceStore.put("trace.snapshot.suppressed.contentText.errorType",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
            // ignore
        }

        Map<String, Object> md = metadataToMap(mdObj);
        String url = firstNonBlank(firstString(md.get("url")), firstString(md.get("link")), firstString(md.get("source")));
        String title = firstNonBlank(firstString(md.get("title")), firstString(md.get("document_title")), firstString(md.get("name")));
        String snippet = firstNonBlank(firstString(md.get("snippet")), firstString(md.get("summary")), firstString(md.get("description")));

        String host = null;
        if (url != null && !url.isBlank()) {
            try {
                host = URI.create(url).getHost();
            } catch (Throwable ignore) {
                TraceStore.put("trace.snapshot.suppressed.contentHost", true);
                TraceStore.put("trace.snapshot.suppressed.contentHost.errorType",
                        SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
                host = null;
            }
        }

        m.put("title", SafeRedactor.diagnosticValue("title", title, 400));
        m.put("url", SafeRedactor.diagnosticValue("url", url, 1000));
        m.put("host", limit(host, 180));
        m.put("snippet", SafeRedactor.diagnosticValue("snippet", snippet, 600));

        if (text != null) {
            m.put("textLen", text.length());
            m.put("textHash12", SafeRedactor.hash12(text));
        }

        Double score = tryExtractScore(c);
        if (score != null) {
            m.put("score", score);
        }

        // Keep original metadata in redacted, JSON-safe form.
        m.put("metadata", sanitizeValue(md));
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataToMap(Object mdObj) {
        if (mdObj == null) return Map.of();
        try {
            if (mdObj instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                int i = 0;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e == null || e.getKey() == null) continue;
                    out.put(String.valueOf(e.getKey()), e.getValue());
                    if (++i >= 128) break;
                }
                return out;
            }

            if (mdObj instanceof dev.langchain4j.data.document.Metadata metadata) {
                return copyMetadata(metadata.toMap());
            }
            if (mdObj instanceof dev.langchain4j.rag.query.Metadata metadata) {
                return copyMetadata(com.example.lms.service.rag.QueryUtils.metadata(metadata));
            }
        } catch (Throwable ignore) {
            logSuppressed("metadata.copy", ignore);
        }
        return Map.of("_meta", limit(String.valueOf(mdObj), 800));
    }

    private static Map<String, Object> copyMetadata(Map<String, ?> map) {
        if (map == null || map.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, ?> e : map.entrySet()) {
            if (e == null || e.getKey() == null) continue;
            out.put(e.getKey(), e.getValue());
            if (++i >= 128) break;
        }
        return out;
    }

    private Double tryExtractScore(Content c) {
        if (c == null || c.textSegment() == null) return null;
        Map<String, Object> metadata = metadataToMap(c.textSegment().metadata());
        for (String key : List.of("score", "relevanceScore", "rerankScore", "vectorScore")) {
            Object value = metadata.get(key);
            if (value instanceof Number n) return n.doubleValue();
            if (value instanceof String s) {
                try {
                    return Double.valueOf(s.trim());
                } catch (NumberFormatException ignore) {
                    TraceStore.put("trace.snapshot.suppressed.scoreParse", true);
                    TraceStore.put("trace.snapshot.suppressed.scoreParse.errorType", "invalid_number");
                    // continue
                }
            }
        }
        return null;
    }

    private Object sanitizeValue(Object v) {
        if (v == null) return null;
        if (v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Enum<?>) {
            return SafeRedactor.diagnosticValue(null, v, maxValueLen);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            int i = 0;
            for (Map.Entry<?, ?> me : m.entrySet()) {
                if (me == null || me.getKey() == null) continue;
                String key = String.valueOf(me.getKey());
                out.put(safeTraceKey(key), SafeRedactor.diagnosticValue(key, me.getValue(), maxValueLen));
                if (++i >= 64) break;
            }
            return out;
        }
        if (v instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (Object o : it) {
                out.add(sanitizeValue(o));
                if (++i >= 64) break;
            }
            return out;
        }
        // Fallback: stringify only through the diagnostic summarizer.
        return SafeRedactor.diagnosticValue(null, String.valueOf(v), maxValueLen);
    }

    private static boolean hasPrefix(Map<String, Object> raw, String prefix) {
        if (raw == null || raw.isEmpty() || prefix == null) return false;
        for (String k : raw.keySet()) {
            if (k != null && k.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String firstString(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof java.util.List<?> list && !list.isEmpty()) {
            return firstString(list.get(0));
        }
        return String.valueOf(v);
    }

    private static int intValue(Object v) {
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return n.intValue();
            }
            TraceStore.put("trace.snapshot.suppressed.intValue", true);
            TraceStore.put("trace.snapshot.suppressed.intValue.errorType", "invalid_number");
            return 0;
        }
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ignore) {
            TraceStore.put("trace.snapshot.suppressed.intValue", true);
            TraceStore.put("trace.snapshot.suppressed.intValue.errorType", "invalid_number");
            return 0;
        }
    }

    private String wrapHtmlIfNeeded(
            String html,
            String snapshotId,
            String tsIso,
            String sid,
            String traceId,
            String requestId,
            String reason,
            String method,
            String path,
            Integer status,
            String err
    ) {
        if (html == null) return null;
        String t = html.trim();
        if (t.startsWith("<!doctype") || t.startsWith("<html")) {
            return html;
        }
        StringBuilder sb = new StringBuilder(Math.max(4096, html.length() + 2048));
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"/>");
        sb.append("<title>Trace Snapshot ").append(htmlEscape(snapshotId)).append("</title>");
        sb.append("<style>")
                .append("body{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:16px}")
                .append("table{border-collapse:collapse;margin-bottom:12px}")
                .append("td,th{border:1px solid #ddd;padding:6px 8px}")
                .append("th{background:#f7f7f7;text-align:left}")
                .append(".mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace}")
                .append("</style>");
        sb.append("</head><body>");
        sb.append("<h2>Trace Snapshot</h2>");
        sb.append("<table>");
        row(sb, "id", snapshotId);
        row(sb, "ts", tsIso);
        row(sb, "sid", sid);
        row(sb, "traceId", traceId);
        row(sb, "requestId", requestId);
        row(sb, "reason", reason);
        row(sb, "method", method);
        row(sb, "path", path);
        row(sb, "status", (status == null ? "" : String.valueOf(status)));
        if (err != null && !err.isBlank()) {
            row(sb, "error", err);
        }
        sb.append("</table>");
        sb.append(html);
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static String safeRequestPath(String path) {
        String s = safe(path).trim();
        if (s.isEmpty()) {
            return "";
        }
        int query = s.indexOf('?');
        int fragment = s.indexOf('#');
        int cut = -1;
        if (query >= 0 && fragment >= 0) {
            cut = Math.min(query, fragment);
        } else if (query >= 0) {
            cut = query;
        } else if (fragment >= 0) {
            cut = fragment;
        }
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        if (s.isBlank()) {
            return "/";
        }
        String masked = SafeRedactor.redact(s);
        if (masked != null && !masked.equals(s)) {
            return SafeRedactor.diagnosticText("http.path", s, 180);
        }
        return SafeRedactor.safeMessage(s, 180);
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on") || s.equals("y");
    }

    private static boolean isDbgSearch(Map<String, String> mdc, Map<String, Object> rawTrace) {
        try {
            if (mdc != null && truthy(mdc.get("dbgSearch"))) return true;
            if (rawTrace != null && truthy(rawTrace.get("dbg.search.enabled"))) return true;
            if (rawTrace != null && truthy(rawTrace.get("dbg.search.boost.active"))) return true;
            if (rawTrace != null && truthy(rawTrace.get("dbgSearch"))) return true;
        } catch (Throwable ignore) {
            logSuppressed("debugSearch.detect", ignore);
        }
        return false;
    }

    private boolean passesFilters(String reason, Map<String, Object> rawTrace) {
        String r = safe(reason).trim();
        if (!denyReasonsCsv.isBlank() && matchesAnyPattern(r, denyReasonsCsv)) {
            return false;
        }
        if (!allowReasonsCsv.isBlank()) {
            if (!matchesAnyPattern(r, allowReasonsCsv)) {
                return false;
            }
        }

        if (rawTrace == null || rawTrace.isEmpty()) {
            // No keys to match; allow unless allow-keys is configured.
            return allowKeysCsv == null || allowKeysCsv.isBlank();
        }

        if (denyKeysCsv != null && !denyKeysCsv.isBlank()) {
            if (matchesAnyKeyPattern(rawTrace, denyKeysCsv)) {
                return false;
            }
        }

        if (allowKeysCsv != null && !allowKeysCsv.isBlank()) {
            String mode = (allowKeysMode == null) ? "any" : allowKeysMode.trim().toLowerCase(Locale.ROOT);
            boolean all = mode.equals("all");
            return matchesAllowKeys(rawTrace, allowKeysCsv, all);
        }

        return true;
    }

    private static boolean matchesAllowKeys(Map<String, Object> rawTrace, String allowCsv, boolean all) {
        java.util.List<String> pats = splitCsv(allowCsv);
        if (pats.isEmpty()) return true;
        if (all) {
            for (String p : pats) {
                if (!matchesAnyKeyPattern(rawTrace, p)) {
                    return false;
                }
            }
            return true;
        }
        return matchesAnyKeyPattern(rawTrace, allowCsv);
    }

    private static boolean matchesAnyKeyPattern(Map<String, Object> rawTrace, String csvOrPattern) {
        if (rawTrace == null || rawTrace.isEmpty()) return false;
        java.util.List<String> pats = splitCsv(csvOrPattern);
        for (String k : rawTrace.keySet()) {
            if (k == null) continue;
            for (String p : pats) {
                if (globMatch(k, p)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesAnyPattern(String value, String csv) {
        if (value == null) return false;
        for (String p : splitCsv(csv)) {
            if (globMatch(value, p)) {
                return true;
            }
        }
        return false;
    }

    private static void logSuppressed(String stage, Throwable ignored) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("[TRACE_SNAPSHOT] suppressed stage={}",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        }
    }

    private static java.util.List<String> splitCsv(String csv) {
        if (csv == null) return java.util.List.of();
        String t = csv.trim();
        if (t.isEmpty()) return java.util.List.of();
        String[] parts = t.split(",");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static boolean globMatch(String value, String pattern) {
        if (value == null) return false;
        if (pattern == null || pattern.isBlank()) return false;
        String p = pattern.trim();
        if (p.equals("*")) return true;
        // Fast paths
        if (!p.contains("*")) {
            return value.equals(p);
        }

        // Simple glob matcher: '*' matches any substring.
        String[] parts = p.split("\\*", -1);
        if (parts.length == 0) return value.equals(p);

        boolean startsWithStar = p.startsWith("*");
        boolean endsWithStar = p.endsWith("*");
        int idx = 0;

        int firstPart = 0;
        if (!startsWithStar) {
            String pref = parts[0];
            if (!value.startsWith(pref)) return false;
            idx = pref.length();
            firstPart = 1;
        }

        int lastPart = parts.length - 1;
        int endLimit = value.length();
        if (!endsWithStar) {
            String suf = parts[lastPart];
            if (!value.endsWith(suf)) return false;
            endLimit = value.length() - suf.length();
            lastPart--;
        }

        for (int i = firstPart; i <= lastPart; i++) {
            String mid = parts[i];
            if (mid == null || mid.isEmpty()) continue;
            int pos = value.indexOf(mid, idx);
            if (pos < 0 || pos > endLimit) return false;
            idx = pos + mid.length();
        }

        return true;
    }

    private boolean shouldCapture(
            String reason,
            boolean dbgSearch,
            boolean hasMl,
            boolean hasOrch,
            boolean hasException,
            boolean statusTrigger
    ) {
        String r = safe(reason).trim();
        boolean isHttp = r.equalsIgnoreCase("http_request") || r.equalsIgnoreCase("http");
        if (!isHttp) {
            // Non-HTTP reasons are explicit capture sites (e.g., vector_flush_error, embedding_failover)
            // and should always be retained when enabled.
            return true;
        }

        boolean should = false;
        if (hasException && captureHttpOnException) should = true;
        if (statusTrigger) should = true;
        if (dbgSearch && captureHttpOnDebug) should = true;
        if (hasMl && captureHttpOnMl) should = true;
        if (hasOrch && captureHttpOnOrch) should = true;
        return should;
    }

    private boolean consumeBudget(String traceId, long nowMs) {
        // guard: if no limits configured, always allow
        if (maxPerTrace <= 0 && minIntervalMs <= 0) return true;

        String key = (traceId == null || traceId.isBlank()) ? "(no-trace)" : traceId;
        try {
            if (budgets.size() > 8192) {
                budgets.clear();
            }
        } catch (Throwable ignore) {
            logSuppressed("captureBudget.evict", ignore);
        }

        CaptureBudget b = budgets.computeIfAbsent(key, __ -> new CaptureBudget(nowMs));
        synchronized (b) {
            if (budgetWindowMs > 0 && nowMs - b.firstAtMs > budgetWindowMs) {
                b.firstAtMs = nowMs;
                b.lastAtMs = nowMs;
                b.count = 0;
            }
            if (maxPerTrace > 0 && b.count >= maxPerTrace) {
                return false;
            }
            if (b.count > 0 && minIntervalMs > 0 && nowMs - b.lastAtMs < minIntervalMs) {
                return false;
            }
            b.count++;
            b.lastAtMs = nowMs;
            return true;
        }
    }

    private String buildHtmlSnapshot(
            String snapshotId,
            String tsIso,
            String sid,
            String traceId,
            String requestId,
            String reason,
            String method,
            String path,
            Integer status,
            String err,
            Map<String, String> mdc,
            Map<String, Object> trace
    ) {
        try {
            com.example.lms.service.trace.TraceHtmlBuilder b = htmlBuilderProvider.getIfAvailable();
            if (b != null) {
                return b.buildSnapshotHtml(
                        snapshotId,
                        tsIso,
                        sid,
                        traceId,
                        requestId,
                        reason,
                        method,
                        path,
                        status,
                        err,
                        trace,
                        mdc
                );
            }
        } catch (Throwable ignore) {
            logSuppressed("htmlBuilder.snapshot", ignore);
        }

        // Fallback: simple HTML listing.
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"/>");
        sb.append("<title>Trace Snapshot ").append(htmlEscape(snapshotId)).append("</title>");
        sb.append("<style>body{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:16px} table{border-collapse:collapse} td,th{border:1px solid #ddd;padding:6px 8px} th{background:#f7f7f7;text-align:left} pre{white-space:pre-wrap;background:#f7f7f7;padding:12px;border:1px solid #eee}</style>");
        sb.append("</head><body>");
        sb.append("<h2>Trace Snapshot</h2>");
        sb.append("<table>");
        row(sb, "id", snapshotId);
        row(sb, "ts", tsIso);
        row(sb, "sid", sid);
        row(sb, "traceId", traceId);
        row(sb, "requestId", requestId);
        row(sb, "reason", reason);
        row(sb, "method", method);
        row(sb, "path", path);
        row(sb, "status", (status == null ? "" : String.valueOf(status)));
        row(sb, "error", (err == null ? "" : err));
        sb.append("</table>");

        sb.append("<h3>TraceStore</h3><pre>");
        if (trace != null) {
            for (Map.Entry<String, Object> e : trace.entrySet()) {
                if (e == null) continue;
                sb.append(htmlEscape(String.valueOf(e.getKey()))).append(" = ")
                        .append(htmlEscape(String.valueOf(e.getValue()))).append("\n");
            }
        }
        sb.append("</pre>");

        sb.append("<h3>MDC</h3><pre>");
        if (mdc != null) {
            for (Map.Entry<String, String> e : mdc.entrySet()) {
                if (e == null) continue;
                sb.append(htmlEscape(String.valueOf(e.getKey()))).append(" = ")
                        .append(htmlEscape(String.valueOf(e.getValue()))).append("\n");
            }
        }
        sb.append("</pre>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String k, String v) {
        if (sb == null) return;
        sb.append("<tr><th>").append(htmlEscape(k)).append("</th><td>")
                .append(htmlEscape(v)).append("</td></tr>");
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String limit(String s, int maxLen) {
        if (s == null) return null;
        int m = Math.max(16, maxLen);
        if (s.length() <= m) return s;
        return s.substring(0, m) + "...";
    }
}
