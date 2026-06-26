package ai.abandonware.nova.orch.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A tiny in-process backoff/jitter scheduler for HTTP 429 / rate-limit signals.
 *
 * <p>Service 레이어 수정이 어려운 환경에서도, AOP 레이어에서 "지연/스케줄링" 우회 적용이 가능하도록
 * "지금 호출해도 되는가"(cooldown gate)와 "429이 발생했을 때 다음 허용 시각"을 중앙에서 관리합니다.
 *
 * <p>아키텍처:
 * <ul>
 *   <li>429(또는 rate-limit 계열) 신호를 받으면 retry-after(있으면) + exp backoff + jitter로
 *       cooldownUntil을 계산해 저장</li>
 *   <li>다음 호출이 들어오면 cooldownUntil 전까지는 "즉시 스킵"(sleep 대신 gate)하여
 *       burst/retry 폭주를 완화</li>
 * </ul>
 */
public class RateLimitBackoffCoordinator {
    private static final Logger log = LoggerFactory.getLogger(RateLimitBackoffCoordinator.class);

    public static final String PROVIDER_BRAVE = "brave";
    public static final String PROVIDER_NAVER = "naver";

    private final Environment env;
    private final ConcurrentHashMap<String, BackoffState> states = new ConcurrentHashMap<>();

    public RateLimitBackoffCoordinator(Environment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    public Decision shouldSkip(String provider) {
        if (!isEnabled()) {
            return Decision.allow();
        }
        BackoffState st = states.computeIfAbsent(safeProvider(provider), p -> new BackoffState());
        long now = System.currentTimeMillis();
        long until = st.cooldownUntilEpochMs.get();
        if (until <= now) {
            return Decision.allow();
        }

        boolean justStarted = false;
        long js = justStartedMs();
        if (js > 0L) {
            long last = st.lastRateLimitEpochMs.get();
            if (last > 0L) {
                justStarted = (now - last) <= js;
            }
        }

        return Decision.skip(until - now, st.lastReason.get(), justStarted);
    }


    public void recordRateLimited(String provider, Long retryAfterMs, String reason) {
        recordRateLimited(provider, retryAfterMs, reason, null);
    }

    /**
     * @param retryAfterValue raw Retry-After header value (delta-seconds or HTTP-date) if available
     */
    public void recordRateLimited(String provider, Long retryAfterMs, String reason, String retryAfterValue) {
        if (!isEnabled()) {
            return;
        }
        BackoffState st = states.computeIfAbsent(safeProvider(provider), p -> new BackoffState());
        long now = System.currentTimeMillis();

        long parsedRetryAfterMs = (retryAfterValue == null || retryAfterValue.isBlank())
                ? 0L
                : parseRetryAfterMs(retryAfterValue, now);
        long expBackoffMs = computeExpBackoffMs(st.consecutive429.get() + 1);

        // Combine Retry-After (provider hint) with exp-backoff (self-protection) using MAX,
        // so we don't underestimate cooldown and cause retry storms.
        long baseMs = maxPositive(
                retryAfterMs,
                (parsedRetryAfterMs > 0L) ? parsedRetryAfterMs : null,
                expBackoffMs
        );
        long clampedBaseMs = clampMs(baseMs, minBackoffMs(), maxBackoffMs());
        long jitterMs = computeJitterMs(clampedBaseMs);
        long delayMs = clampMs(clampedBaseMs + jitterMs, minBackoffMs(), maxBackoffMs());

        long newUntil = now + delayMs;
        st.cooldownUntilEpochMs.updateAndGet(prev -> Math.max(prev, newUntil));
        st.lastRateLimitEpochMs.set(now);
        st.lastReason.set(safe(reason));
        st.consecutive429.incrementAndGet();

        if (isDebug()) {
            log.debug("[Nova] rate-limit backoff set: provider={}, delayMs={}, baseMs={}, jitterMs={}, retryAfterMs={}, retryAfterValue='{}', reason='{}'",
                    safeProvider(provider), delayMs, clampedBaseMs, jitterMs, retryAfterMs, safe(retryAfterValue), safe(reason));
        }
    }

    public void recordSuccess(String provider) {
        BackoffState st = states.get(safeProvider(provider));
        if (st == null) {
            return;
        }
        // conservative reset: keep cooldownUntil but reset consecutive counter, so subsequent 429 doesn't explode.
        st.consecutive429.set(0);
        st.lastReason.set("");
    }

    private boolean isEnabled() {
        return getBoolean(true,
                "nova.orch.web.failsoft.ratelimit-backoff.enabled",
                "nova.orch.web.failsoft.rateLimitBackoff.enabled",
                "nova.orch.web.ratelimit.aop-backoff.enabled");
    }

    private boolean isDebug() {
        return getBoolean(false,
                "nova.orch.web.failsoft.ratelimit-backoff.debug",
                "nova.orch.web.failsoft.rateLimitBackoff.debug",
                "nova.orch.web.ratelimit.aop-backoff.debug");
    }

    private long minBackoffMs() {
        return clampMs(getLong(200L,
                "nova.orch.web.failsoft.ratelimit-backoff.min-ms",
                "nova.orch.web.ratelimit.aop-backoff.min-ms"), 0L, 60_000L);
    }

    private long maxBackoffMs() {
        return clampMs(getLong(30_000L,
                "nova.orch.web.failsoft.ratelimit-backoff.max-ms",
                "nova.orch.web.ratelimit.aop-backoff.max-ms"), 1_000L, 5 * 60_000L);
    }

    private long justStartedMs() {
        return clampMs(getLong(80L,
                "nova.orch.web.failsoft.ratelimit-backoff.just-started-ms",
                "nova.orch.web.ratelimit.aop-backoff.just-started-ms"), 0L, 5_000L);
    }

    private double jitterRatio() {
        return clampDouble(getDouble(0.20,
                "nova.orch.web.failsoft.ratelimit-backoff.jitter-ratio",
                "nova.orch.web.ratelimit.aop-backoff.jitter-ratio"), 0.0, 1.0);
    }

    private long computeJitterMs(long baseMs) {
        double r = jitterRatio();
        if (r <= 0.0 || baseMs <= 0L) {
            return 0L;
        }
        long maxJitter = (long) Math.floor(baseMs * r);
        if (maxJitter <= 0L) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(0L, maxJitter + 1L);
    }

    private long computeExpBackoffMs(int consecutive) {
        int n = Math.max(1, consecutive);
        int capped = Math.min(n, 10); // 2^10 = 1024x cap
        long min = minBackoffMs();
        long backoff = min * (1L << (capped - 1));
        return clampMs(backoff, min, maxBackoffMs());
    }

    /**
     * Retry-After: delta-seconds or HTTP-date.
     */
    public static long parseRetryAfterMs(String value, long nowEpochMs) {
        String v = safe(value).trim();
        if (v.isEmpty()) {
            return 0L;
        }
        // delta-seconds
        try {
            long seconds = Long.parseLong(v);
            if (seconds <= 0L) {
                return 0L;
            }
            return Math.min(seconds * 1000L, 5 * 60_000L);
        } catch (NumberFormatException ignore) {
            // fall through
        }
        // HTTP-date (best effort)
        try {
            ZonedDateTime dt = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            long target = dt.toInstant().toEpochMilli();
            long delta = target - nowEpochMs;
            return Math.max(0L, delta);
        } catch (DateTimeParseException ignore) {
            // best effort: try parse in local zone if missing
            try {
                ZonedDateTime dt = ZonedDateTime.parse(v);
                long target = dt.toInstant().toEpochMilli();
                long delta = target - nowEpochMs;
                return Math.max(0L, delta);
            } catch (Throwable ignore2) {
                return 0L;
            }
        }
    }

    private static String safeProvider(String provider) {
        String p = safe(provider).trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty()) {
            return "unknown";
        }
        return p;
    }

    private boolean getBoolean(boolean def, String... keys) {
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            String v = env.getProperty(k);
            if (v == null) continue;
            String t = v.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("on")) return true;
            if (t.equals("false") || t.equals("0") || t.equals("no") || t.equals("off")) return false;
        }
        return def;
    }

    private long getLong(long def, String... keys) {
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            String v = env.getProperty(k);
            if (v == null) continue;
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException ignore) {
                // continue
            }
        }
        return def;
    }

    private double getDouble(double def, String... keys) {
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            String v = env.getProperty(k);
            if (v == null) continue;
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException ignore) {
                // continue
            }
        }
        return def;
    }

    private static long clampMs(long v, long min, long max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static double clampDouble(double v, double min, double max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static long maxPositive(Long a, Long b, Long c) {
        long m = 0L;
        if (a != null && a > m) m = a;
        if (b != null && b > m) m = b;
        if (c != null && c > m) m = c;
        return m;
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static final class BackoffState {
        private final AtomicLong cooldownUntilEpochMs = new AtomicLong(0L);
        private final AtomicInteger consecutive429 = new AtomicInteger(0);
        private final AtomicLong lastRateLimitEpochMs = new AtomicLong(0L);
        private final AtomicReference<String> lastReason = new AtomicReference<>("");
    }

    public record Decision(boolean skip, long remainingMs, String reason, boolean justStarted) {
        public static Decision allow() {
            return new Decision(false, 0L, "", false);
        }

        public static Decision skip(long remainingMs, String reason, boolean justStarted) {
            return new Decision(true, Math.max(0L, remainingMs), safe(reason), justStarted);
        }

        public boolean shouldSkip() {
            return skip;
        }
    }
}

