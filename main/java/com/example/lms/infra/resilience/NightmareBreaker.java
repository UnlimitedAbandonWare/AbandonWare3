package com.example.lms.infra.resilience;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import dev.langchain4j.exception.HttpException;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * NightmareBreaker:
 * - 오케스트레이션 단계(전처리/보조 LLM/메인 LLM)에서 발생하는 timeout/blank/429 등을
 * key 단위로 집계하고, 짧은 기간 OPEN 상태로 만들어 연쇄 지연을 차단한다.
 *
 * 개선 포인트 (UAW + Errorxs log 기반)
 * - 성공 시 연속 카운터 리셋(비연속 blank 누적 방지)
 * - slow-call / silent-failure 기반 trip 옵션
 * - 공통 실행 래퍼 execute(...) 제공
 * - 예외 분류 classify(Throwable) 중앙집중화
 */
public class NightmareBreaker {

    private static final Logger log = LoggerFactory.getLogger(NightmareBreaker.class);

    /**
     * Request-scoped trace key: Map&lt;breakerKey, openAtEpochMillis&gt;.
     *
     * <p>AuxBlockTracker 가 "breakerOpenAt" 을 best-effort 로 채울 때 활용합니다.</p>
     */
    public static final String TRACE_OPEN_AT_MS_KEY = "nightmare.breaker.openAtMs";

    /**
     * Request-scoped trace key: Map&lt;breakerKey, openUntilEpochMillis&gt; (optional).
     *
     * <p>동시에 여러 breaker 가 OPEN 일 수 있기 때문에, openUntil 은 key별로 기록합니다.</p>
     */
    public static final String TRACE_OPEN_UNTIL_MS_KEY = "nightmare.breaker.openUntilMs";

    /** Request-scoped trace key: Map<breakerKey, FailureKind> (optional). */
    public static final String TRACE_OPEN_KIND_KEY = "nightmare.breaker.openKind";

    /** Request-scoped trace key: Map<breakerKey, lastErrorMessage> (optional). */
    public static final String TRACE_OPEN_ERRMSG_KEY = "nightmare.breaker.openErrMsg";

    /** Request-scoped trace key for ecosystem recirculation breadcrumbs. */
    public static final String TRACE_ECOSYSTEM_RECIRCULATE_KEY = "ecosystem.breaker.recirculate";

    /** Request-scoped trace key for ecosystem low-trust surge breadcrumbs. */
    public static final String TRACE_ECOSYSTEM_AMMONIA_SURGE_KEY = "ecosystem.breaker.ammoniaSurge";

    /**
     * Request-scoped trace key: 마지막으로 관측한 openUntilEpochMillis (optional, quick debugging).
     *
     * <p>TRACE_OPEN_UNTIL_MS_KEY 를 key별 map 으로 저장하면서도, 단일 값으로 빠르게 확인할 수 있게 남깁니다.</p>
     */
    public static final String TRACE_OPEN_UNTIL_MS_LAST_KEY = "nightmare.breaker.openUntilMs.last";

    public enum FailureKind {
        TIMEOUT,
        INTERRUPTED,
        REJECTED,
        RATE_LIMIT,

        /** Configuration/request schema error (e.g. "model is required"). */
        CONFIG,

        HTTP_4XX,
        HTTP_5XX,
        EMPTY_RESPONSE,
        UNKNOWN
    }

    /** Circuit breaker mode: CLOSED → OPEN → HALF_OPEN → CLOSED. */
    public enum BreakerMode {
        CLOSED, OPEN, HALF_OPEN
    }

    private final NightmareBreakerProperties props;

    // Optional: when the breaker opens, force dbgSearch console tracing for a short window
    // to unmask silent failures (UAW: Anti-Fragile / Unmasking).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.trace.SearchDebugBoost searchDebugBoost;

    /** Structured observability for NightmareBreaker (DebugEventStore is optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DebugEventStore debugEventStore;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NightmareBreakerProperties.EffectivePolicy> policyCache = new ConcurrentHashMap<>();

    public NightmareBreaker(NightmareBreakerProperties props) {
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${nightmare.breaker.evict-interval-ms:300000}")
    void evictStaleStates() {
        if (!props.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - 300_000L;
        int before = states.size();
        states.entrySet().removeIf(entry -> {
            State state = entry.getValue();
            return state != null
                    && state.lastActivityMs() < cutoff
                    && !(state.mode == BreakerMode.OPEN && state.openUntilMs > now);
        });
        policyCache.keySet().removeIf(k -> !states.containsKey(k));
        int removed = Math.max(0, before - states.size());
        if (removed > 0) {
            log.debug("[NightmareBreaker] evicted stale states removed={} remaining={}", removed, states.size());
        }
    }

    private void emitEvent(DebugEventLevel level,
            String fingerprint,
            String message,
            String where,
            Map<String, Object> data,
            Throwable error) {
        DebugEventStore store = this.debugEventStore;
        if (store == null) {
            return;
        }
        try {
            store.emit(
                    DebugProbeType.NIGHTMARE_BREAKER,
                    (level == null ? DebugEventLevel.INFO : level),
                    fingerprint,
                    message,
                    where,
                    data,
                    error);
        } catch (Throwable ignore) {
            recordDebugEventEmitFailure(ignore);
        }
    }

    private void recordDebugEventEmitFailure(Throwable failure) {
        try {
            TraceStore.inc("nightmare.debugEvent.emit.failed");
            if (failure != null) {
                TraceStore.put("nightmare.debugEvent.emit.failureClass", "nightmare_debug_event_emit_failed");
            }
        } catch (Throwable ignore) {
            log.trace("[NightmareBreaker] DebugEventStore failure breadcrumb failed: {}",
                    ignore.getClass().getSimpleName());
        }
    }

    private boolean isRequestFailSoftBypassEnabled(
            String key,
            long remainingMs,
            long openAtMs,
            long openUntilMs,
            FailureKind lastKind) {
        try {
            GuardContext ctx = GuardContextHolder.get();
            if (ctx == null || !ctx.planBool("breaker.failSoft", false)) {
                return false;
            }
            String diagnosticKey = safeBreakerKey(key);
            TraceStore.put("nightmare.breaker.failSoft.bypassed", true);
            TraceStore.put("nightmare.breaker.failSoft.key", diagnosticKey);
            TraceStore.put("nightmare.breaker.failSoft.remainingMs", Math.max(0L, remainingMs));
            TraceStore.put("nightmare.breaker.failSoft.openSinceMs", openAtMs);
            TraceStore.put("nightmare.breaker.failSoft.openUntilMs", openUntilMs);
            TraceStore.put("nightmare.breaker.failSoft.kind", String.valueOf(lastKind));
            TraceStore.inc("nightmare.breaker.failSoft.bypass.count");
            log.info("[NightmareBreaker] fail-soft bypass for open breaker key={} kind={} remainingMs={}",
                    diagnosticKey, lastKind, Math.max(0L, remainingMs));
            return true;
        } catch (Throwable ignored) {
            traceSuppressed("nightmare.failSoftBypass", ignored);
            return false;
        }
    }

    private NightmareBreakerProperties.EffectivePolicy policy(String key) {
        return policyCache.computeIfAbsent(key, props::policyFor);
    }

    public boolean isOpen(String key) {
        return remainingOpenMs(key) > 0;
    }

    public boolean isOpen(String key, String stage) {
        return isOpen(stageKey(key, stage));
    }

    /**
     * Returns true if the circuit is OPEN or HALF_OPEN for the given key.
     * Useful for pre-call checks in query transformers and aux helpers.
     */
    public boolean isOpenOrHalfOpen(String key) {
        if (!props.isEnabled())
            return false;
        State s = states.get(key);
        if (s == null)
            return false;
        if (s.mode == BreakerMode.OPEN && remainingOpenMs(key) > 0)
            return true;
        return s.mode == BreakerMode.HALF_OPEN;
    }

    public boolean isOpenOrHalfOpen(String key, String stage) {
        return isOpenOrHalfOpen(stageKey(key, stage));
    }

    /**
     * Useful for orchestration gating without repeating null/loop checks.
     */
    public boolean isAnyOpen(String... keys) {
        if (!props.isEnabled() || keys == null || keys.length == 0) {
            return false;
        }
        for (String k : keys) {
            if (k == null)
                continue;
            if (isOpenOrHalfOpen(k))
                return true;
        }
        return false;
    }

    /**
     * Returns true if any breaker key in the current state map starts with the given prefix
     * and is OPEN.
     *
     * <p>We use this when the breaker key is parameterized (e.g. "chat-draft:<model>") and
     * higher-level orchestration only knows the logical prefix.</p>
     */
    public boolean isAnyOpenPrefix(String prefix) {
        if (!props.isEnabled() || prefix == null || prefix.isBlank()) {
            return false;
        }
        try {
            for (String k : states.keySet()) {
                if (k != null && k.startsWith(prefix) && isOpenOrHalfOpen(k)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            traceSuppressed("nightmare.prefixScan", e);
            tracePrefixScanFailure(prefix, e);
        }
        return false;
    }

    private static void tracePrefixScanFailure(String prefix, Throwable e) {
        TraceStore.put("nightmare.prefixScan.failed", true);
        TraceStore.put("nightmare.prefixScan.prefixHash", SafeRedactor.hashValue(prefix));
        TraceStore.put("nightmare.prefixScan.prefixLength", prefix == null ? 0 : prefix.length());
        TraceStore.put("nightmare.prefixScan.errorKind", String.valueOf(classify(e)));
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("nightmare.suppressed." + safeStage, true);
        TraceStore.put("nightmare.suppressed." + safeStage + ".errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    /**
     * Record HTTP 429 / rate-limit style failures in a uniform way.
     */
    public void recordRateLimit(String key, String context, String reason) {
        recordRateLimit(key, context, null, reason, null);
    }

    /**
     * Record a RATE_LIMIT (ex: HTTP 429) with an optional retry-after / cooldown hint.
     *
     * <p>This is used to avoid breaker-poisoning (429 counted as generic failure) and to honor
     * Retry-After cooldowns when available.</p>
     */
    public void recordRateLimit(String key, String context, String reason, Long retryAfterMs) {
        recordRateLimit(key, context, null, reason, retryAfterMs);
    }

    /**
     * Record a RATE_LIMIT (ex: HTTP 429) with an optional retry-after / cooldown hint, preserving the original error.
     */
    public void recordRateLimit(String key, String context, Throwable error, String reason, Long retryAfterMs) {
        // Normalize early to keep trace keys stable.
        String k = (key == null) ? "" : key.trim();
        if (k.isEmpty()) {
            return;
        }
        String traceKey = safeBreakerKey(k);

        // COOLDOWN is a *local* gate (not a remote 429). We must not accumulate it into breaker
        // counters/backoff; otherwise OPEN propagation explodes and causes skip storms.
        String lowerReason = safeLower(reason);
        if (lowerReason.contains("cooldown")) {
            try {
                TraceStore.putIfAbsent("nightmare.rateLimit.cooldown." + traceKey, Boolean.TRUE);
                if (reason != null && !reason.isBlank()) {
                    TraceStore.putIfAbsent("nightmare.rateLimit.cooldown.reason." + traceKey, SafeRedactor.traceLabelOrFallback(reason, "unknown"));
                }
            } catch (Throwable ignore) {
                traceSuppressed("nightmare.rateLimitCooldownTrace", ignore);
            }
            return;
        }

        // Suppress duplicate RATE_LIMIT recording within the same request for the same key.
        // (e.g. HTTP_429 -> cooldown -> cooldown...) This prevents exponential backoff escalation.
        String onceKey = "nightmare.rateLimit.once." + traceKey;
        Object prev;
        try {
            prev = TraceStore.putIfAbsent(onceKey, Boolean.TRUE);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.rateLimitOnceTrace", ignore);
            prev = null;
        }
        if (prev != null) {
            try {
                TraceStore.inc("nightmare.rateLimit.dup." + traceKey);
                if (reason != null && !reason.isBlank()) {
                    TraceStore.put("nightmare.rateLimit.dup.lastReason." + traceKey, SafeRedactor.traceLabelOrFallback(reason, "unknown"));
                }
            } catch (Throwable ignore) {
                traceSuppressed("nightmare.rateLimitDuplicateTrace", ignore);
            }
            return;
        }

        Throwable err = (error != null) ? error : (reason == null ? null : new RuntimeException(reason));
        recordFailure(k, FailureKind.RATE_LIMIT, err, context, retryAfterMs);
    }

    /**
     * Record HTTP 403 / rejected-style failures (bot detection, quota, etc).
     */
    public void recordRejected(String key, String context, String reason) {
        recordFailure(key, FailureKind.REJECTED,
                (reason == null ? null : new RuntimeException(reason)), context);
    }

    /**
     * Record timeout-style failures.
     */
    public void recordTimeout(String key, String context, String reason) {
        recordFailure(key, FailureKind.TIMEOUT,
                (reason == null ? null : new RuntimeException(reason)), context);
    }

    public long remainingOpenMs(String key) {
        if (!props.isEnabled())
            return 0;
        State s = states.get(key);
        if (s == null)
            return 0;
        if (s.mode != BreakerMode.OPEN)
            return 0;
        long now = System.currentTimeMillis();
        long remain = s.openUntilMs - now;

        // NOTE: When a breaker was opened by a previous request, we still want to surface the *global*
        // open-since timestamp for this request (used by AuxBlockTracker's breakerOpenAt field).
        if (remain > 0) {
            long openSince = s.openSinceMs;
            if (openSince <= 0) {
                // Best-effort fallback (should be rare): approximate openSince from openUntil - openDuration.
                try {
                    NightmareBreakerProperties.EffectivePolicy cfg = policy(key);
                    long dur = (cfg != null && cfg.openDuration() != null)
                            ? cfg.openDuration().toMillis()
                            : 0L;
                    openSince = (dur > 0) ? Math.max(0L, s.openUntilMs - dur) : now;
                } catch (Throwable ignore) {
                    traceSuppressed("nightmare.openSincePolicy", ignore);
                    openSince = now;
                }
            }
            recordOpenAtForTrace(key, openSince, s.openUntilMs);
            recordOpenMetaForTrace(key, s.lastKind,
                    (s.lastError != null ? SafeRedactor.traceLabelOrFallback(s.lastError.getMessage(), "") : ""));
        }

        return Math.max(0, remain);
    }

    public long remainingOpenMs(String key, String stage) {
        return remainingOpenMs(stageKey(key, stage));
    }

    private static String stageKey(String key, String stage) {
        String base = key == null ? "" : key.trim();
        if (base.isEmpty()) {
            return "";
        }
        String st = stage == null ? "" : stage.trim();
        if (st.isEmpty()) {
            return base;
        }
        String normalizedStage = st.replaceAll("[^a-zA-Z0-9._:-]+", "_");
        return base + ":" + normalizedStage;
    }

    /**
     * Debug/Probe용 상태 조회.
     * - 운영 로직에 영향 없이 현재 OPEN 여부/잔여시간/최근 실패 종류를 관찰한다.
     * - Probe/Soak/오케스트레이션 디버깅에서 '왜 우회(bypass)됐는지'를 재현 가능하게 한다.
     */
    public StateView inspect(String key) {
        if (key == null) {
            return new StateView(null, null, false, 0L, 0L, 0L, null,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }
        State s = states.get(key);
        if (s == null) {
            return new StateView(key, BreakerMode.CLOSED, false, 0L, 0L, 0L, null,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }
        long now = System.currentTimeMillis();
        long remain = Math.max(0L, s.openUntilMs - now);
        boolean open = (s.mode == BreakerMode.OPEN && remain > 0L);
        long openSince = s.openSinceMs;
        if (open && openSince <= 0L) {
            try {
                NightmareBreakerProperties.EffectivePolicy cfg = policy(key);
                if (cfg != null && cfg.openDuration() != null) {
                    long durMs = cfg.openDuration().toMillis();
                    if (durMs > 0L) {
                        openSince = Math.max(0L, s.openUntilMs - durMs);
                    }
                }
            } catch (Throwable ignore) {
                traceSuppressed("nightmare.inspectOpenSincePolicy", ignore);
            }
        }
        String lastMsg = (s.lastError == null) ? null
                : SafeRedactor.traceLabelOrFallback(s.lastError.getMessage(), "");
        return new StateView(
                key,
                s.mode,
                open,
                openSince,
                s.openUntilMs,
                remain,
                s.lastKind,
                s.consecutiveFailures.get(),
                s.consecutiveTimeouts.get(),
                s.consecutiveRateLimits.get(),
                s.consecutiveRejected.get(),
                s.consecutiveInterrupts.get(),
                s.consecutiveBlanks.get(),
                s.consecutiveSilentFailures.get(),
                s.consecutiveSlowCalls.get(),
                s.consecutiveSuccesses.get(),
                s.trialCalls.get(),
                lastMsg);
    }

    /**
     * Debug/진단용 전체 스냅샷.
     * <p>
     * {@link #inspect(String)}는 단일 key만 조회하므로,
     * 운영 중 전체 복구/차단 상태를 한 화면에 보여주기 위해 제공한다.
     * </p>
     */
    public Map<String, StateView> snapshot() {
        // deterministic ordering helps diffs/ops.
        Map<String, StateView> out = new TreeMap<>();
        for (String key : states.keySet()) {
            out.put(key, inspect(key));
        }
        return out;
    }

    /**
     * 외부 노출용 가벼운 상태 스냅샷(불변).
     * Probe 응답에 그대로 넣어도 되는 수준의 메타만 포함한다.
     */
    public static final class StateView {
        public final String key;
        public final BreakerMode mode;
        public final boolean open;
        /**
         * The timestamp (epoch millis) when the breaker last transitioned into OPEN.
         * <p>
         * This is tracked in the breaker global state (not request-scoped observation).
         */
        public final long openSinceMs;
        public final long openUntilMs;
        public final long remainingMs;
        public final FailureKind lastKind;
        public final int consecutiveFailures;
        public final int consecutiveTimeouts;
        public final int consecutiveRateLimits;
        public final int consecutiveRejected;
        public final int consecutiveInterrupts;
        public final int consecutiveBlanks;
        public final int consecutiveSilentFailures;
        public final int consecutiveSlowCalls;
        public final int consecutiveSuccesses;
        public final int trialCalls;
        public final String lastErrorMessage;

        public StateView(String key,
                BreakerMode mode,
                boolean open,
                long openSinceMs,
                long openUntilMs,
                long remainingMs,
                FailureKind lastKind,
                int consecutiveFailures,
                int consecutiveTimeouts,
                int consecutiveRateLimits,
                int consecutiveRejected,
                int consecutiveInterrupts,
                int consecutiveBlanks,
                int consecutiveSilentFailures,
                int consecutiveSlowCalls,
                int consecutiveSuccesses,
                int trialCalls,
                String lastErrorMessage) {
            this.key = key;
            this.mode = mode;
            this.open = open;
            this.openSinceMs = openSinceMs;
            this.openUntilMs = openUntilMs;
            this.remainingMs = remainingMs;
            this.lastKind = lastKind;
            this.consecutiveFailures = consecutiveFailures;
            this.consecutiveTimeouts = consecutiveTimeouts;
            this.consecutiveRateLimits = consecutiveRateLimits;
            this.consecutiveRejected = consecutiveRejected;
            this.consecutiveInterrupts = consecutiveInterrupts;
            this.consecutiveBlanks = consecutiveBlanks;
            this.consecutiveSilentFailures = consecutiveSilentFailures;
            this.consecutiveSlowCalls = consecutiveSlowCalls;
            this.consecutiveSuccesses = consecutiveSuccesses;
            this.trialCalls = trialCalls;
            this.lastErrorMessage = lastErrorMessage;
        }
    }

    public void checkOpenOrThrow(String key) {
        if (!props.isEnabled())
            return;
        State s = states.get(key);
        if (s != null) {
            s.touch();
            long now = System.currentTimeMillis();
            long remain = s.openUntilMs - now;
            if (s.mode == BreakerMode.OPEN && remain > 0) {
                long openAt = s.openSinceMs;
                if (openAt <= 0) {
                    try {
                        NightmareBreakerProperties.EffectivePolicy cfg = policy(key);
                        long dur = (cfg != null && cfg.openDuration() != null) ? cfg.openDuration().toMillis() : 0L;
                        openAt = (dur > 0) ? Math.max(0L, s.openUntilMs - dur) : now;
                    } catch (Throwable ignored) {
                        traceSuppressed("nightmare.openAtPolicy", ignored);
                        openAt = now;
                    }
                }
                recordOpenAtForTrace(key, openAt, s.openUntilMs);
                recordOpenMetaForTrace(key, s.lastKind,
                        (s.lastError != null ? SafeRedactor.traceLabelOrFallback(s.lastError.getMessage(), "") : ""));

                try {
                    String diagnosticKey = safeBreakerKey(key);
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    dd.put("key", diagnosticKey);
                    dd.put("remainingMs", remain);
                    dd.put("openSinceMs", openAt);
                    dd.put("openUntilMs", s.openUntilMs);
                    dd.put("kind", String.valueOf(s.lastKind));
                    emitEvent(
                            DebugEventLevel.INFO,
                            "nightmare.open.block." + diagnosticKey,
                            "NightmareBreaker open-circuit: blocked call",
                            "NightmareBreaker.checkOpenOrThrow",
                            dd,
                            null);
                } catch (Throwable ignore) {
                    traceSuppressed("nightmare.openBlockEvent", ignore);
                }
                if (isRequestFailSoftBypassEnabled(key, remain, openAt, s.openUntilMs, s.lastKind)) {
                    return;
                }
                throw new OpenCircuitException(key, Duration.ofMillis(remain), s.lastKind);
            }

            // OPEN time elapsed → HALF_OPEN trial
            if (s.mode == BreakerMode.OPEN && remain <= 0) {
                if (props.isHalfOpenEnabled()) {
                    String diagnosticKey = safeBreakerKey(key);
                    log.info("[NightmareBreaker] HALF_OPEN trial start: key={}", diagnosticKey);
                    s.mode = BreakerMode.HALF_OPEN;
                    s.trialCalls.set(0);
                    s.consecutiveSuccesses.set(0);

                    try {
                        java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                        dd.put("key", diagnosticKey);
                        dd.put("mode", "HALF_OPEN");
                        emitEvent(
                                DebugEventLevel.INFO,
                                "nightmare.half_open.start." + diagnosticKey,
                                "NightmareBreaker HALF_OPEN trial start",
                                "NightmareBreaker.checkOpenOrThrow",
                                dd,
                                null);
                    } catch (Throwable ignore) {
                        traceSuppressed("nightmare.halfOpenStartEvent", ignore);
                    }
                } else {
                    // legacy: just close
                    s.mode = BreakerMode.CLOSED;
                    s.openUntilMs = 0;
                    s.openSinceMs = 0;
                }
            }

            // HALF_OPEN: limit number of trial calls
            if (s.mode == BreakerMode.HALF_OPEN && props.isHalfOpenEnabled()) {
                int maxCalls = props.getHalfOpenMaxCalls();
                if (maxCalls > 0 && s.trialCalls.incrementAndGet() > maxCalls) {
                    throw new OpenCircuitException(key, Duration.ZERO, s.lastKind);
                }
            }
        }
    }

    /**
     * 성공 기록:
     * - OPEN 상태였다면 닫고
     * - 연속 카운터는 항상 리셋
     * - slow-call 옵션이 켜져 있으면 느린 응답을 누적해서 OPEN
     */
    public void recordSuccess(String key, long latencyMs) {
        if (!props.isEnabled())
            return;
        State s = states.computeIfAbsent(key, k -> new State());
        s.touch();
        NightmareBreakerProperties.EffectivePolicy cfg = policy(key);

        if (s.mode == BreakerMode.HALF_OPEN && props.isHalfOpenEnabled()) {
            int succ = s.consecutiveSuccesses.incrementAndGet();
            int threshold = props.getHalfOpenSuccessThreshold();
            if (threshold > 0 && succ >= threshold) {
                String diagnosticKey = safeBreakerKey(key);
                log.info("[NightmareBreaker] CLOSED (HALF_OPEN success) key={} latencyMs={}", diagnosticKey, latencyMs);
                s.mode = BreakerMode.CLOSED;
                s.openUntilMs = 0;
                s.openSinceMs = 0;
                s.trialCalls.set(0);

                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    dd.put("key", diagnosticKey);
                    dd.put("latencyMs", latencyMs);
                    emitEvent(
                            DebugEventLevel.INFO,
                            "nightmare.closed.half_open." + diagnosticKey,
                            "NightmareBreaker CLOSED (HALF_OPEN success)",
                            "NightmareBreaker.recordSuccess",
                            dd,
                            null);
                } catch (Throwable ignore) {
                    traceSuppressed("nightmare.halfOpenCloseEvent", ignore);
                }
            }
        } else if (s.mode == BreakerMode.OPEN) {
            String diagnosticKey = safeBreakerKey(key);
            log.info("[NightmareBreaker] CLOSED key={} latencyMs={}", diagnosticKey, latencyMs);
            s.mode = BreakerMode.CLOSED;
            s.openUntilMs = 0;
            s.openSinceMs = 0;
            s.trialCalls.set(0);
            s.consecutiveSuccesses.set(0);

            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("key", diagnosticKey);
                dd.put("latencyMs", latencyMs);
                emitEvent(
                        DebugEventLevel.INFO,
                        "nightmare.closed." + diagnosticKey,
                        "NightmareBreaker CLOSED",
                        "NightmareBreaker.recordSuccess",
                        dd,
                        null);
            } catch (Throwable ignore) {
                traceSuppressed("nightmare.closeEvent", ignore);
            }
        }

        // ✅ 핵심: 성공이면 항상 연속 카운터를 초기화(비연속 blank 누적 방지)
        s.consecutiveFailures.set(0);
        s.consecutiveTimeouts.set(0);
        s.consecutiveRateLimits.set(0);
        s.consecutiveRejected.set(0);
        s.consecutiveInterrupts.set(0);
        s.consecutiveBlanks.set(0);
        s.consecutiveSilentFailures.set(0);

        if (cfg.tripOnSlowCall() && latencyMs >= cfg.slowCallThresholdMs()) {
            int n = s.consecutiveSlowCalls.incrementAndGet();
            if (n >= cfg.slowCallThreshold()) {
                tripOpen(key, s, FailureKind.REJECTED, null,
                        "slow_call " + latencyMs + "ms", "slow-call");
            }
        } else {
            s.consecutiveSlowCalls.set(0);
        }
    }

    public void recordBlank(String key, String context) {
        if (!props.isEnabled())
            return;
        NightmareBreakerProperties.EffectivePolicy cfg = policy(key);
        if (!cfg.tripOnBlank())
            return;
        State s = states.computeIfAbsent(key, k -> new State());
        s.touch();

        int blanks = s.consecutiveBlanks.incrementAndGet();
        s.lastKind = FailureKind.EMPTY_RESPONSE;

        int ctxLen = (context == null) ? 0 : context.length();
        String diagnosticKey = safeBreakerKey(key);

        // TraceStore anchor (safe): do NOT store context text; only ctxLen.
        try {
            long ts = System.currentTimeMillis();
            java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
            ev.put("ts", ts);
            ev.put("key", diagnosticKey);
            ev.put("ctxLen", ctxLen);
            ev.put("n", blanks);
            ev.put("threshold", cfg.blankThreshold());
            TraceStore.put("nightmare.blank.lastKey", diagnosticKey);
            TraceStore.put("nightmare.blank.last", ev);
            TraceStore.append("nightmare.blank.events", ev);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.blankTrace", ignore);
        }

        try {
            java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
            dd.put("key", diagnosticKey);
            dd.put("ctxLen", ctxLen);
            dd.put("blanks", blanks);
            dd.put("threshold", cfg.blankThreshold());
            emitEvent(
                    DebugEventLevel.WARN,
                    "nightmare.blank." + diagnosticKey,
                    "NightmareBreaker blank-response",
                    "NightmareBreaker.recordBlank",
                    dd,
                    null);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.blankEvent", ignore);
        }

        if (blanks >= cfg.blankThreshold()) {
            tripOpen(key, s, FailureKind.EMPTY_RESPONSE, null, context, "blank-threshold");
        } else {
            log.warn("[NightmareBreaker] blank-response key={} blanks={}/{} contextHash={} contextLength={}",
                    diagnosticKey, blanks, cfg.blankThreshold(), SafeRedactor.hashValue(context), ctxLen);
        }
    }

    public void recordSilentFailure(String key, String context, String reason) {
        if (!props.isEnabled())
            return;
        NightmareBreakerProperties.EffectivePolicy cfg = policy(key);
        if (!cfg.tripOnSilentFailure())
            return;
        State s = states.computeIfAbsent(key, k -> new State());
        s.touch();

        int n = s.consecutiveSilentFailures.incrementAndGet();
        s.lastKind = FailureKind.EMPTY_RESPONSE;

        int ctxLen = (context == null) ? 0 : context.length();
        String diagnosticKey = safeBreakerKey(key);
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        boolean hasReason = reason != null && !reason.isBlank();

        // TraceStore anchor (safe): do NOT store context text; only ctxLen.
        try {
            long ts = System.currentTimeMillis();
            java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
            ev.put("ts", ts);
            ev.put("key", diagnosticKey);
            ev.put("ctxLen", ctxLen);
            ev.put("n", n);
            ev.put("threshold", cfg.silentFailureThreshold());
            if (hasReason) {
                ev.put("reason", safeReason);
            }
            TraceStore.put("nightmare.silent.lastKey", diagnosticKey);
            TraceStore.put("nightmare.silent.last", ev);
            TraceStore.append("nightmare.silent.events", ev);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.silentTrace", ignore);
        }

        try {
            java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
            dd.put("key", diagnosticKey);
            dd.put("ctxLen", ctxLen);
            dd.put("n", n);
            dd.put("threshold", cfg.silentFailureThreshold());
            if (hasReason) {
                dd.put("reason", safeReason);
            }
            emitEvent(
                    DebugEventLevel.WARN,
                    "nightmare.silent." + diagnosticKey,
                    "NightmareBreaker silent-failure",
                    "NightmareBreaker.recordSilentFailure",
                    dd,
                    null);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.silentEvent", ignore);
        }

        if (n >= cfg.silentFailureThreshold()) {
            tripOpen(key, s, FailureKind.EMPTY_RESPONSE, null,
                    "silent-failure reason=" + safeReason
                            + " contextHash=" + SafeRedactor.hashValue(context) + " contextLength=" + ctxLen,
                    "silent-failure");
        } else {
            log.warn("[NightmareBreaker] silent-failure key={} n={}/{} reason={} contextHash={} contextLength={}",
                    diagnosticKey, n, cfg.silentFailureThreshold(), safeReason,
                    SafeRedactor.hashValue(context), ctxLen);
        }
    }

    public void recordFailure(String key, FailureKind kind, Throwable error, String context) {
        recordFailure(key, kind, error, context, null);
    }

    /**
     * Record a failure with an optional open-duration hint (milliseconds).
     *
     * <p>Primarily used for RATE_LIMIT (HTTP 429) where Retry-After / cooldown headers can suggest
     * an appropriate open duration.</p>
     */
    public void recordFailure(String key, FailureKind kind, Throwable error, String context, Long openDurationHintMs) {
        if (!props.isEnabled())
            return;
        if (key == null || key.isBlank()) {
            return;
        }

        // Normalize UNKNOWN failures: some call sites still pass UNKNOWN (legacy),
        // but we can classify TIMEOUT/INTERRUPTED/RATE_LIMIT to avoid breaker poisoning.
        FailureKind normalized = (kind == null) ? FailureKind.UNKNOWN : kind;
        try {
            if (normalized == FailureKind.UNKNOWN && error != null) {
                FailureKind inferred = classify(error);
                if (inferred != null && inferred != FailureKind.UNKNOWN) {
                    normalized = inferred;
                }
            }
            if (normalized == FailureKind.RATE_LIMIT && openDurationHintMs == null) {
                openDurationHintMs = tryExtractRetryAfterMs(error);
            }
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.kindNormalize", ignore);
        }
        kind = normalized;

        State s = states.computeIfAbsent(key, k -> new State());
        s.touch();
        NightmareBreakerProperties.EffectivePolicy cfg = policy(key);

        s.lastKind = kind;
        s.lastError = error;

        // 실패 유형이 바뀌면 blank/silent/slow 누적은 끊는 게 안전
        s.consecutiveBlanks.set(0);
        s.consecutiveSilentFailures.set(0);
        s.consecutiveSlowCalls.set(0);

        // Interrupted is frequently a cancellation/teardown signal, not a provider-side timeout.
        // Do not let it pollute TIMEOUT aggregation or the generic failure threshold.
        boolean countsAsFailure = countsAsFailureForThreshold(kind);

        int total;
        if (countsAsFailure) {
            total = s.consecutiveFailures.incrementAndGet();
        } else {
            total = s.consecutiveFailures.get();
            if (kind == FailureKind.INTERRUPTED) {
                // Treat interrupt as a streak-breaker for TIMEOUT to avoid accidental threshold crossings.
                s.consecutiveTimeouts.set(0);
            }
        }

        if (kind == FailureKind.TIMEOUT)
            s.consecutiveTimeouts.incrementAndGet();
        if (kind == FailureKind.RATE_LIMIT)
            s.consecutiveRateLimits.incrementAndGet();
        if (kind == FailureKind.REJECTED || kind == FailureKind.CONFIG)
            s.consecutiveRejected.incrementAndGet();
        if (kind == FailureKind.INTERRUPTED)
            s.consecutiveInterrupts.incrementAndGet();

        // HALF_OPEN: any failure re-opens immediately (but still respects kind-specific open durations/backoff)
        if (s.mode == BreakerMode.HALF_OPEN && props.isHalfOpenEnabled()) {
            // Treat INTERRUPTED as a local cancellation signal; do not re-open unless explicitly configured.
            if (kind == FailureKind.INTERRUPTED && !props.isTripOnInterrupt()) {
                try {
                    TraceStore.inc("nightmare.interrupt.halfOpen.ignored." + safeBreakerKey(key));
                } catch (Exception ignore) {
                    traceSuppressed("nightmare.halfOpenInterruptTrace", ignore);
                }
                return;
            }

            Duration openFor = computeOpenDurationForTrip(cfg, kind, s, openDurationHintMs);
            tripOpen(key, s, kind, error, context, "half-open-failure", openFor);
            return;
        }

        boolean tripTimeout = (kind == FailureKind.TIMEOUT && s.consecutiveTimeouts.get() >= cfg.timeoutThreshold());
        boolean tripRateLimit = (kind == FailureKind.RATE_LIMIT && s.consecutiveRateLimits.get() >= cfg.rateLimitThreshold());
        boolean tripRejected = ((kind == FailureKind.REJECTED || kind == FailureKind.CONFIG) && s.consecutiveRejected.get() >= cfg.rejectedThreshold());
        boolean tripInterrupt = (props.isTripOnInterrupt()
                && kind == FailureKind.INTERRUPTED
                && s.consecutiveInterrupts.get() >= cfg.interruptThreshold());
        boolean tripFailure = (countsAsFailure && total >= cfg.failureThreshold());

        boolean shouldTrip = tripTimeout || tripRateLimit || tripRejected || tripInterrupt || tripFailure;

        if (shouldTrip) {
            String reason;
            if (tripTimeout) {
                reason = "timeout-threshold";
            } else if (tripRateLimit) {
                reason = "rate-limit-threshold";
            } else if (tripRejected) {
                reason = (kind == FailureKind.CONFIG) ? "config-threshold" : "rejected-threshold";
            } else if (tripInterrupt) {
                reason = "interrupt-threshold";
            } else {
                reason = "failure-threshold";
            }

            Duration openFor = computeOpenDurationForTrip(cfg, kind, s, openDurationHintMs);
            tripOpen(key, s, kind, error, context, reason, openFor);
        }
    }

    private boolean countsAsFailureForThreshold(FailureKind kind) {
        if (kind == null) {
            return true;
        }
        if (kind == FailureKind.INTERRUPTED) {
            return false;
        }
        if (kind == FailureKind.RATE_LIMIT) {
            return props.isRateLimitCountsAsFailure();
        }
        if (kind == FailureKind.TIMEOUT) {
            return props.isTimeoutCountsAsFailure();
        }
        return true;
    }

    private Duration computeOpenDurationForTrip(
            NightmareBreakerProperties.EffectivePolicy cfg,
            FailureKind kind,
            State s,
            Long openDurationHintMs
    ) {
        Duration fallback = (cfg != null && cfg.openDuration() != null) ? cfg.openDuration() : Duration.ofSeconds(15);

        // RATE_LIMIT: honor Retry-After / cooldown hint + exponential backoff
        if (kind == FailureKind.RATE_LIMIT) {
            long baseMs = safeToMs(props.getRateLimitOpenDuration(), safeToMs(fallback, 15_000L));
            long capMs = safeToMs(props.getRateLimitMaxOpenDuration(), Math.max(baseMs, safeToMs(fallback, 15_000L)));
            if (openDurationHintMs != null && openDurationHintMs > 0) {
                baseMs = Math.max(baseMs, openDurationHintMs);
            }
            int n = Math.max(1, s != null ? s.consecutiveRateLimits.get() : 1);
            long ms = applyExponentialBackoff(baseMs, n, props.getBackoffBase(), capMs);
            return Duration.ofMillis(ms);
        }

        // TIMEOUT: exponential backoff (separate from generic failure threshold)
        if (kind == FailureKind.TIMEOUT) {
            long baseMs = safeToMs(props.getTimeoutOpenDuration(), safeToMs(fallback, 15_000L));
            long capMs = safeToMs(props.getTimeoutMaxOpenDuration(), Math.max(baseMs, safeToMs(fallback, 15_000L)));
            // Optional: allow call-site to cap TIMEOUT opens (useful for optional stages like QueryTransformer)
            if (openDurationHintMs != null && openDurationHintMs > 0) {
                capMs = Math.min(capMs, openDurationHintMs);
                baseMs = Math.min(baseMs, capMs);
            }
            int n = Math.max(1, s != null ? s.consecutiveTimeouts.get() : 1);
            long ms = applyExponentialBackoff(baseMs, n, props.getBackoffBase(), capMs);
            return Duration.ofMillis(ms);
        }

        // CONFIG failures are usually non-transient (ex: missing model). Keep the breaker open longer.
        if (kind == FailureKind.CONFIG) {
            try {
                Duration d = props.getConfigOpenDuration();
                if (d != null && !d.isZero() && !d.isNegative() && d.compareTo(fallback) > 0) {
                    return d;
                }
            } catch (Throwable ignore) {
                traceSuppressed("nightmare.configDuration", ignore);
            }
        }

        return fallback;
    }

    private static long safeToMs(Duration d, long defaultMs) {
        if (d == null) {
            return defaultMs;
        }
        try {
            long ms = d.toMillis();
            return ms > 0 ? ms : defaultMs;
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.safeToMs", ignore);
            return defaultMs;
        }
    }

    private static long applyExponentialBackoff(long baseMs, int attempt, double backoffBase, long capMs) {
        long base = Math.max(1L, baseMs);
        long cap = capMs > 0 ? capMs : Long.MAX_VALUE;

        int exp = Math.max(0, attempt - 1);
        double b = (backoffBase > 1.0d) ? backoffBase : 2.0d;

        double factor;
        try {
            factor = Math.pow(b, exp);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.backoffPow", ignore);
            factor = 1.0d;
        }

        double v = ((double) base) * factor;
        long ms;
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) {
            ms = base;
        } else if (v >= (double) Long.MAX_VALUE) {
            ms = Long.MAX_VALUE;
        } else {
            ms = (long) v;
        }

        if (ms < base) {
            ms = base;
        }
        if (ms > cap) {
            ms = cap;
        }
        return ms;
    }

    /**
     * 공통 실행 래퍼:
     * - open이면 fallback
     * - 실행 성공 시 recordSuccess / badResult면 recordBlank or recordSilentFailure
     * - 예외면 classify 후 recordFailure + fallback
     */
    public <T> T execute(String key,
            String context,
            Supplier<T> call,
            Predicate<T> isBadResult,
            Supplier<T> fallback) {
        if (!props.isEnabled()) {
            return call.get();
        }

        try {
            checkOpenOrThrow(key);
        } catch (OpenCircuitException oce) {
            traceSuppressed("nightmare.executeOpenCircuitFallback", oce);
            return fallback != null ? fallback.get() : null;
        }

        long started = System.nanoTime();
        try {
            T out = call.get();
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;

            boolean bad = (isBadResult != null && isBadResult.test(out));
            if (bad) {
                if (out instanceof String s) {
                    if (s == null || s.isBlank()) {
                        recordBlank(key, context);
                    } else {
                        recordSilentFailure(key, context, "bad_result");
                    }
                } else {
                    recordSilentFailure(key, context, "bad_result");
                }
            } else {
                recordSuccess(key, latencyMs);
            }

            return out;
        } catch (Throwable t) {
            traceSuppressed("nightmare.executeFailureFallback", t);
            FailureKind kind = classify(t);

            if (kind == FailureKind.INTERRUPTED) {
                // Avoid poisoning pooled workers with lingering interrupt status.
                // NOTE: Interrupted is frequently a cancellation/teardown signal; do NOT count as TIMEOUT.
                Thread.interrupted();
                recordFailure(key, kind, t, context);
            } else if (kind == FailureKind.RATE_LIMIT) {
                // Prefer recordRateLimit() so we can honor Retry-After hints and suppress duplicate 429 signals per request.
                Long retryAfterMs = tryExtractRetryAfterMs(t);
                String reason = "RATE_LIMIT";
                try {
                    if (t instanceof HttpException he && he.statusCode() == 429) {
                        reason = "HTTP 429";
                    } else if (t instanceof WebClientResponseException w && w.getStatusCode().value() == 429) {
                        reason = "HTTP 429";
                    } else if (safeLower(t.getMessage()).contains("429")) {
                        reason = "HTTP 429";
                    }
                } catch (Throwable ignore) {
                    traceSuppressed("nightmare.executeRateLimitReason", ignore);
                }
                recordRateLimit(key, context, t, reason, retryAfterMs);
            } else {
                recordFailure(key, kind, t, context);
            }

            return fallback != null ? fallback.get() : null;
        }
    }

    public RuntimeException wrap(FailureKind kind, Throwable cause) {
        if (cause instanceof NightmareBreakException nbe)
            return nbe;
        return new NightmareBreakException(kind, cause);
    }

    private void tripOpen(String key, State s, FailureKind kind, Throwable error, String context, String reason) {
        tripOpen(key, s, kind, error, context, reason, null);
    }

    private void tripOpen(String key, State s, FailureKind kind, Throwable error, String context, String reason, Duration openForOverride) {
        long now = System.currentTimeMillis();
        boolean alreadyOpen = (s.mode == BreakerMode.OPEN) && (s.openUntilMs > now);
        NightmareBreakerProperties.EffectivePolicy cfg = policy(key);
        Duration openFor = (openForOverride != null ? openForOverride : cfg.openDuration());

        // CONFIG failures are usually non-transient (ex: missing model). Keep the breaker open longer
        // to avoid hot retry loops.
        if (kind == FailureKind.CONFIG) {
            try {
                Duration d = props.getConfigOpenDuration();
                if (d != null && !d.isZero() && !d.isNegative()) {
                    if (d.compareTo(openFor) > 0) {
                        openFor = d;
                    }
                }
            } catch (Throwable ignore) {
                traceSuppressed("nightmare.tripOpenConfigDuration", ignore);
            }
        }
        long candidateUntil = now + Math.max(1L, openFor.toMillis());
        long openUntil = alreadyOpen ? Math.max(s.openUntilMs, candidateUntil) : candidateUntil;

        // Track the actual open-since time (global breaker state), not just the time we observed it
        // in this particular request.
        if (!alreadyOpen || s.openSinceMs <= 0L) {
            s.openSinceMs = now;
        }

        s.openUntilMs = openUntil;
        s.mode = BreakerMode.OPEN;
        s.trialCalls.set(0);
        s.consecutiveSuccesses.set(0);
        s.lastKind = kind;
        s.lastError = error;

        String contextHash = SafeRedactor.hashValue(context);
        int contextLength = (context == null) ? 0 : context.length();
        String contextDiagnostic = "contextHash=" + (contextHash == null ? "" : contextHash)
                + " contextLength=" + contextLength;
        String msg = (error != null) ? SafeRedactor.traceLabelOrFallback(error.getMessage(), "") : "";
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        String diagnosticKey = safeBreakerKey(key);

        if (cfg.logStackTrace() && error != null) {
            log.warn("[NightmareBreaker] OPEN key={} kind={} reason={} openFor={} contextHash={} contextLength={}",
                    diagnosticKey, kind, safeReason, openFor, contextHash, contextLength, error);
        } else {
            log.warn("[NightmareBreaker] OPEN key={} kind={} reason={} openFor={} err={} contextHash={} contextLength={}",
                    diagnosticKey, kind, safeReason, openFor, msg, contextHash, contextLength);
        }

        // Best-effort: record open timestamp into the request TraceStore for later analysis
        // (e.g., AuxBlockTracker can show breakerOpenAt).
        recordOpenAtForTrace(key, (s.openSinceMs > 0L ? s.openSinceMs : now), openUntil);
        recordOpenMetaForTrace(key, kind, msg);

        try {
            java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
            dd.put("key", diagnosticKey);
            dd.put("kind", (kind != null ? kind.name() : "UNKNOWN"));
            if (safeReason != null && !safeReason.isBlank()) {
                dd.put("reason", safeReason);
            }
            dd.put("alreadyOpen", alreadyOpen);
            dd.put("openForMs", openFor.toMillis());
            dd.put("openSinceMs", (s.openSinceMs > 0L ? s.openSinceMs : now));
            dd.put("openUntilMs", openUntil);
            dd.put("ctxLen", contextLength);
            dd.put("contextHash", contextHash);
            emitEvent(
                    DebugEventLevel.WARN,
                    "nightmare.open." + diagnosticKey,
                    "NightmareBreaker OPEN",
                    "NightmareBreaker.tripOpen",
                    dd,
                    error);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.openEvent", ignore);
        }

        // [Auto Debug Boost] When the breaker opens, enable dbgSearch for N minutes
        // so that the next requests have console diagnostics without per-request toggles.
        try {
            if (searchDebugBoost != null) {
                searchDebugBoost.maybeBoostOnNightmareOpen(
                        diagnosticKey,
                        kind != null ? kind.name() : null,
                        safeReason,
                        openFor,
                        contextDiagnostic
                );
            }
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.searchDebugBoost", ignore);
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * Record breaker open timing into the request-local trace store.
     * <p>
     * We intentionally store the breaker global "openSince" (not the current/observed time),
     * so downstream (e.g., AuxBlockTracker) can render a consistent breakerOpenAt.
     */
    private void recordOpenAtForTrace(String key, long openSinceMs, long openUntilMs) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            String traceKey = safeBreakerKey(key);
            Object val = TraceStore.get(TRACE_OPEN_AT_MS_KEY);
            Map<String, Long> byKey;
            if (val instanceof Map<?, ?> map) {
                byKey = (Map<String, Long>) map;
            } else {
                byKey = new ConcurrentHashMap<>();
                TraceStore.put(TRACE_OPEN_AT_MS_KEY, byKey);
            }
            byKey.putIfAbsent(traceKey, openSinceMs);

            Object untilVal = TraceStore.get(TRACE_OPEN_UNTIL_MS_KEY);
            Map<String, Long> byKeyUntil;
            if (untilVal instanceof Map<?, ?> map) {
                byKeyUntil = (Map<String, Long>) map;
            } else {
                byKeyUntil = new ConcurrentHashMap<>();
                TraceStore.put(TRACE_OPEN_UNTIL_MS_KEY, byKeyUntil);
            }
            // Keep the max(openUntil) per key; openUntil should not go backwards while OPEN,
            // but using max guards against any clock or ordering quirks.
            byKeyUntil.merge(traceKey, openUntilMs, Math::max);

            // Also keep the most recent open-until as a scalar (useful for quick debugging).
            TraceStore.put(TRACE_OPEN_UNTIL_MS_LAST_KEY, openUntilMs);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.openAtTrace", ignore);
        }
    }

    @SuppressWarnings("unchecked")
    private void recordOpenMetaForTrace(String key, FailureKind kind, String errorMessage) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            String traceKey = safeBreakerKey(key);
            Object kindObj = TraceStore.get(TRACE_OPEN_KIND_KEY);
            Map<String, String> kindMap;
            if (kindObj instanceof Map<?, ?> m) {
                kindMap = (Map<String, String>) m;
            } else {
                kindMap = new ConcurrentHashMap<>();
                TraceStore.put(TRACE_OPEN_KIND_KEY, kindMap);
            }
            kindMap.put(traceKey, (kind != null ? kind.name() : "UNKNOWN"));

            Object msgObj = TraceStore.get(TRACE_OPEN_ERRMSG_KEY);
            Map<String, String> msgMap;
            if (msgObj instanceof Map<?, ?> m2) {
                msgMap = (Map<String, String>) m2;
            } else {
                msgMap = new ConcurrentHashMap<>();
                TraceStore.put(TRACE_OPEN_ERRMSG_KEY, msgMap);
            }
            msgMap.put(traceKey, SafeRedactor.safeMessage(errorMessage, 220));
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.openMetaTrace", ignore);
        }
    }


    private static String safeBreakerKey(String key) {
        return SafeRedactor.traceLabelOrFallback(key, "unknown");
    }

    private static String clip(String s, int max) {
        if (s == null || max <= 0)
            return "";
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() <= max)
            return t;
        return t.substring(0, max) + "...";
    }

    public static FailureKind classify(Throwable t) {
        Throwable root = unwrap(t);
        if (root == null)
            return FailureKind.UNKNOWN;

        if (root instanceof InterruptedException)
            return FailureKind.INTERRUPTED;
        if (root instanceof java.util.concurrent.CancellationException)
            return FailureKind.INTERRUPTED;

        // Reactor (or other libs) may use non-JDK cancel exception types.
        try {
            String cn = root.getClass().getName();
            if (cn != null) {
                String lcn = cn.toLowerCase();
                if (lcn.contains("cancel") && lcn.contains("exception")) {
                    return FailureKind.INTERRUPTED;
                }
            }
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.classifyClassName", ignore);
        }
        if (root instanceof TimeoutException || root instanceof HttpTimeoutException)
            return FailureKind.TIMEOUT;

        if (root instanceof WebClientResponseException w) {
            int sc = w.getStatusCode().value();
            String wm = safeLower(w.getMessage());
            if (sc == 400 && isModelRequiredMsg(wm))
                return FailureKind.CONFIG;
            if (sc == 429)
                return FailureKind.RATE_LIMIT;
            if (sc == 503)
                return FailureKind.REJECTED;
            if (sc >= 400 && sc < 500)
                return FailureKind.HTTP_4XX;
            if (sc >= 500)
                return FailureKind.HTTP_5XX;
        }

        if (root instanceof HttpException he) {
            int sc = he.statusCode();
            String hm = safeLower(he.getMessage());
            if (sc == 400 && isModelRequiredMsg(hm))
                return FailureKind.CONFIG;
            if (sc == 400) {
                String m = safeLower(he.getMessage());
                if (m.contains("model is required")) {
                    return FailureKind.CONFIG;
                }
            }
            if (sc == 429)
                return FailureKind.RATE_LIMIT;
            if (sc == 503)
                return FailureKind.REJECTED; // 과부하/서버 불가용
            if (sc >= 400 && sc < 500)
                return FailureKind.HTTP_4XX;
            if (sc >= 500)
                return FailureKind.HTTP_5XX;
        }

        String msg = safeLower(root.getMessage());
        if (isModelRequiredMsg(msg))
            return FailureKind.CONFIG;
        if (msg.contains("rate") && msg.contains("limit"))
            return FailureKind.RATE_LIMIT;
        if (msg.contains("timeout") || msg.contains("timed out"))
            return FailureKind.TIMEOUT;
        if (msg.contains("interrupted"))
            return FailureKind.INTERRUPTED;
        if (msg.contains("cancel"))
            return FailureKind.INTERRUPTED;
        if (msg.contains("overloaded") || msg.contains("busy") || msg.contains("reject"))
            return FailureKind.REJECTED;
        return FailureKind.UNKNOWN;
    }



    /**
     * Best-effort extraction of Retry-After into milliseconds (capped).
     *
     * <p>Used to honor HTTP 429 backpressure hints when available.</p>
     */
    private static Long tryExtractRetryAfterMs(Throwable t) {
        try {
            Throwable root = unwrap(t);
            if (root instanceof WebClientResponseException w) {
                return parseRetryAfterMs(w.getHeaders());
            }
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.retryAfterExtract", ignore);
        }
        return null;
    }

    private static Long parseRetryAfterMs(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String v = headers.getFirst("Retry-After");
        if (v == null) {
            return null;
        }
        v = v.trim();
        if (v.isEmpty()) {
            return null;
        }

        // 1) delta-seconds
        boolean allDigits = true;
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            try {
                long seconds = Long.parseLong(v);
                if (seconds <= 0) {
                    return 0L;
                }
                return Math.min(seconds * 1000L, 60_000L);
            } catch (NumberFormatException ignore) {
                traceSuppressed("nightmare.retryAfterSeconds", ignore);
                return null;
            }
        }

        // 2) HTTP-date (RFC 1123)
        try {
            java.time.ZonedDateTime dt = java.time.ZonedDateTime.parse(
                    v,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            long ms = java.time.Duration.between(java.time.Instant.now(), dt.toInstant()).toMillis();
            return Math.max(0L, Math.min(ms, 60_000L));
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.retryAfterDate", ignore);
            return null;
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t == null)
            return null;
        Throwable cur = t;
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < 12) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static String safeLower(String s) {
        return (s == null) ? "" : s.toLowerCase();
    }

    private static boolean isModelRequiredMsg(String lowerMsg) {
        if (lowerMsg == null || lowerMsg.isBlank()) {
            return false;
        }
        // Common OpenAI-compatible error bodies/messages
        if (lowerMsg.contains("model is required")) return true;
        if (lowerMsg.contains("must provide a model")) return true;
        if (lowerMsg.contains("model parameter") && lowerMsg.contains("required")) return true;
        if (lowerMsg.contains("missing required parameter") && lowerMsg.contains("model")) return true;
        return false;
    }

    private static final class State {
        AtomicInteger consecutiveFailures = new AtomicInteger();
        AtomicInteger consecutiveTimeouts = new AtomicInteger();
        AtomicInteger consecutiveRateLimits = new AtomicInteger();
        AtomicInteger consecutiveRejected = new AtomicInteger();
        AtomicInteger consecutiveInterrupts = new AtomicInteger();
        AtomicInteger consecutiveBlanks = new AtomicInteger();
        AtomicInteger consecutiveSilentFailures = new AtomicInteger();
        AtomicInteger consecutiveSlowCalls = new AtomicInteger();
        AtomicInteger consecutiveSuccesses = new AtomicInteger();
        AtomicInteger trialCalls = new AtomicInteger();
        volatile BreakerMode mode = BreakerMode.CLOSED;
        /** When this breaker last transitioned into OPEN (epoch millis). */
        volatile long openSinceMs = 0;
        volatile long openUntilMs = 0;
        volatile long lastActivityMs = System.currentTimeMillis();
        volatile FailureKind lastKind = FailureKind.UNKNOWN;
        volatile Throwable lastError = null;

        void touch() {
            lastActivityMs = System.currentTimeMillis();
        }

        long lastActivityMs() {
            return lastActivityMs;
        }
    }

    public static class NightmareBreakException extends RuntimeException {
        private final FailureKind kind;

        public NightmareBreakException(FailureKind kind, Throwable cause) {
            super("NightmareBreak: " + kind
                    + (cause != null ? String.format(": errorHash=%s errorLength=%d",
                            SafeRedactor.hashValue(String.valueOf(cause)), String.valueOf(cause).length()) : ""), cause);
            this.kind = kind;
        }

        public FailureKind kind() {
            return kind;
        }
    }

    public static class OpenCircuitException extends RuntimeException {
        private final String key;
        private final Duration remaining;
        private final FailureKind lastKind;

        public OpenCircuitException(String key, Duration remaining, FailureKind lastKind) {
            super("NightmareBreaker is OPEN: key=" + safeBreakerKey(key) + ", remaining=" + remaining);
            this.key = key;
            this.remaining = remaining;
            this.lastKind = lastKind;
        }

        public String key() {
            return key;
        }

        public Duration remaining() {
            return remaining;
        }

        public FailureKind lastKind() {
            return lastKind;
        }
    }
}
