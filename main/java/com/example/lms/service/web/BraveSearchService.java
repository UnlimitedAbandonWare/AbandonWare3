package com.example.lms.service.web;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.search.policy.AdaptiveSearchQueryVariants;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeRedactor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.NaverSearchService.SearchResult;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import com.google.common.util.concurrent.RateLimiter;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Thin synchronous Brave search client kept for backwards compatibility with
 * existing LMS controllers. For new code prefer the reactive
 * {@code com.acme.aicore.adapters.search.BraveSearchProvider}.
 *
 * <p>
 * Configuration resolution order for the API key:
 * </p>
 * <ol>
 * <li>gpt-search.brave.subscription-token (preferred)</li>
 * <li>gpt-search.brave.api-key (legacy)</li>
 * <li>search.brave.subscription-token (legacy)</li>
 * <li>search.brave.api-key (legacy)</li>
 * <li>GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN environment variable</li>
 * <li>GPT_SEARCH_BRAVE_API_KEY environment variable</li>
 * <li>BRAVE_SUBSCRIPTION_TOKEN environment variable</li>
 * <li>BRAVE_API_KEY environment variable</li>
 * </ol>
 */
@Service
public class BraveSearchService implements WebSearchProvider {

    // Normalize search query to avoid URI encoding issues (control chars, extra spaces, overly long queries)
    private static String sanitizeQuery(String q) {
        if (q == null) return "";
        String t = q.replaceAll("\\p{Cntrl}", " ").trim();
        t = t.replaceAll("\\s{2,}", " ");
        if (t.length() > 200) t = t.substring(0, 200);
        return t;
    }

    private static String safeHost(String value) {
        if (value == null || value.isBlank()) return "unknown";
        try {
            String host = URI.create(value.trim()).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (Exception ignore) {
            TraceStore.put("web.brave.suppressed.safeHost", true);
            TraceStore.put("web.brave.suppressed.safeHost.errorType",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
            return "invalid";
        }
    }

    private static final Logger log = LoggerFactory.getLogger(BraveSearchService.class);

    /**
     * Guard to prevent calling cache.put(...) for the same key while Spring @Cacheable(sync=true)
     * is computing the value (Caffeine would throw "Recursive update").
     */
    private static final ThreadLocal<Integer> CACHEABLE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static void enterCacheableSection() {
        CACHEABLE_DEPTH.set(CACHEABLE_DEPTH.get() + 1);
    }

    private static void exitCacheableSection() {
        int d = CACHEABLE_DEPTH.get() - 1;
        if (d <= 0) {
            CACHEABLE_DEPTH.remove();
        } else {
            CACHEABLE_DEPTH.set(d);
        }
    }

    private static boolean isInCacheableSection() {
        try {
            return CACHEABLE_DEPTH.get() > 0;
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.cacheableDepth", true);
            TraceStore.put("web.brave.suppressed.cacheableDepth.errorType",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
            return false;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    // [Patch] Brave API 문서 기준: count 파라미터 최대값 20 (Stuff 0, 1, 2, 4 통합 의견 반영)
    private static final int BRAVE_MAX_TOPK = 20;

    @Value("${gpt-search.brave.subscription-token:${gpt-search.brave.api-key:${search.brave.subscription-token:${search.brave.api-key:${GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN:${GPT_SEARCH_BRAVE_API_KEY:${BRAVE_SUBSCRIPTION_TOKEN:${BRAVE_API_KEY:}}}}}}}}")
    private String apiKey;

    @Value("${gpt-search.brave.base-url:${search.brave.base-url:https://api.search.brave.com/res/v1/web/search}}")
    private String baseUrl;

    @Value("${gpt-search.brave.enabled:${search.brave.enabled:true}}")
    private boolean configEnabled;

    @Value("${gpt-search.brave.timeout-ms:${search.brave.timeout-ms:3000}}")
    private int timeoutMs;

    @Value("${gpt-search.brave.adaptive.enabled:true}")
    private boolean adaptiveSearchEnabled = true;

    @Value("${gpt-search.brave.adaptive.max-queries:3}")
    private int adaptiveMaxQueries = 3;

    @Value("${gpt-search.brave.adaptive.max-overall-timeout-ms:3500}")
    private long adaptiveMaxOverallTimeoutMs = 3500L;

    @Value("${gpt-search.brave.adaptive.per-call-floor-ms:700}")
    private long adaptivePerCallFloorMs = 700L;

    // Align Brave client timeouts with the hybrid await deadline margin to avoid
    // systematic await_timeout races (Future.get vs IO/read timeout).
    @Value("${gpt-search.brave.timeout-margin-ms:${gpt-search.hybrid.await.deadline-margin-ms:120}}")
    private long timeoutMarginMs;

    private final RestTemplate restTemplate;
    private final BraveSearchProperties props;
    private final RateLimiter rateLimiter;
    private final double effectiveQpsLimit;
    private final AtomicInteger monthlyRemaining;
    private volatile LocalDate lastResetDate = LocalDate.now();
    private volatile boolean quotaExhausted = false;

    // [Fix] 429 fail-soft + cool-down to prevent request storms.
    private static final long DEFAULT_429_COOLDOWN_MS = 2000L;
    private static final long MAX_429_COOLDOWN_MS = 30_000L;

    // Local QPS backoff cap: keep it short by default to avoid large p95 spikes.
    // Can be raised indirectly by setting gpt-search.brave.local-cooldown-ms above this cap.
    private static final long MAX_LOCAL_COOLDOWN_MS = 2_000L;
    private final java.util.concurrent.atomic.AtomicLong cooldownUntilEpochMs = new java.util.concurrent.atomic.AtomicLong(
            0L);

    // Sanity guards: avoid runaway/stuck cooldowns that can bias providers by excessive SKIPs.
    private final java.util.concurrent.atomic.AtomicLong lastCooldownUntilObserved = new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicLong lastCooldownCheckEpochMs = new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicLong lastCooldownRemainingObserved = new java.util.concurrent.atomic.AtomicLong(0L);

    // Local (client-side) QPS rate-limit streak tracking (RATE_LIMIT_LOCAL).
    // Used to apply exponential backoff + jitter so callers do not spin in a tight retry loop.
    private final java.util.concurrent.atomic.AtomicInteger localRateLimitStreak = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong lastLocalRateLimitAtEpochMs = new java.util.concurrent.atomic.AtomicLong(0L);

    private boolean enabled;
    private volatile String disabledReason = "";

    public record QuotaReservation(boolean managed, boolean reserved) {
        public static QuotaReservation unmanaged() {
            return new QuotaReservation(false, false);
        }

        public static QuotaReservation denied() {
            return new QuotaReservation(true, false);
        }

        public static QuotaReservation granted() {
            return new QuotaReservation(true, true);
        }

        public boolean allowed() {
            return !managed || reserved;
        }
    }

    // Optional: read-through cache access (cache-only escape hatch)
    @Autowired(required = false)
    private CacheManager cacheManager;

    public BraveSearchService(BraveSearchProperties props) {
        this.props = props;
        this.restTemplate = new RestTemplate();
        // Brave free-tier commonly enforces 1 QPS. Clamp configured QPS to a safer
        // ceiling.
        double configuredQps = props.qpsLimit();
        double effectiveQps = Math.min(Math.max(0.1, configuredQps), 0.8d);
        if (configuredQps > effectiveQps) {
            log.info("[Brave] qpsLimit clamped from {} to {} to respect provider rate limits", configuredQps,
                    effectiveQps);
        }
        this.rateLimiter = RateLimiter.create(effectiveQps);
        this.effectiveQpsLimit = effectiveQps;
        this.monthlyRemaining = new AtomicInteger(props.monthlyQuota());
    }

    @PostConstruct
    void init() {
        if (!configEnabled) {
            enabled = false;
            disabledReason = "disabled_by_config";
            log.info("[ProviderGuard] Brave disabled (config flag off){}", LogCorrelation.suffix());
            return;
        }

        if (ConfigValueGuards.isMissing(apiKey)) {
            enabled = false;
            disabledReason = "missing_brave_api_key";
            log.warn("[AWX2AF2][search][brave] provider disabled enabled=false hasKey=false sourceName=brave.api-key/search.brave.api-key disabledReason={}{}",
                    disabledReason, LogCorrelation.suffix());
            // One-line, grep-friendly message (do NOT proceed with blank token).
            log.warn("[ProviderGuard] Brave: 키 없음으로 disable (missing api key){}", LogCorrelation.suffix());
        } else {
            enabled = true;
            disabledReason = "";
            log.info("[Brave] API key loaded successfully");
            log.debug("[Brave] Config: baseUrlHost={} baseUrlHash={} timeout={}ms",
                    safeHost(baseUrl), SafeRedactor.hashValue(baseUrl), timeoutMs);

            // [FIX-E1] Timeout (connect/read) + fail-soft 안정화
            // - RestTemplate 기본 설정은 무기한 대기 가능 → cancel/connection leak 패턴 유발
            // - IMPORTANT: align provider I/O timeout with Hybrid await budgets to avoid
            //   systematic await_timeout(outer Future.get) when the inner call times out later.
            try {
                // Keep a small safety floor but do NOT force 4s when the system budgets 3~3.7s.
                long effectiveTimeoutMs = timeoutMs;
                if (timeoutMarginMs > 0L && timeoutMs > 0) {
                    effectiveTimeoutMs = Math.max(2500L, (long) timeoutMs - Math.max(0L, timeoutMarginMs));
                } else {
                    effectiveTimeoutMs = Math.max(2500L, (long) timeoutMs);
                }
                int t = (int) effectiveTimeoutMs;
                SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
                rf.setConnectTimeout(t);
                rf.setReadTimeout(t);
                this.restTemplate.setRequestFactory(rf);
                log.info(
                        "[Brave] RestTemplate timeouts set: {}ms (connect/read) [timeoutMs={}, marginMs={}]",
                        t,
                        timeoutMs,
                        timeoutMarginMs);
            } catch (Exception e) {
                log.warn("[AWX][search][brave] timeout configuration failed failureReason={} errorType={} baseUrlHost={} timeoutMs={} timeoutMarginMs={}",
                        "timeout-config-error",
                        SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                        safeHost(baseUrl),
                        timeoutMs,
                        timeoutMarginMs);
            }

            this.lastResetDate = LocalDate.now();
            int quota = props.monthlyQuota();
            if (quota > 0) {
                this.monthlyRemaining.set(quota);
                log.info("[Brave] Local monthly quota initialized to {}", quota);
            } else {
                log.info("[Brave] Local monthly quota disabled (monthly-quota <= 0)");
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the last known disable reason (best-effort).
     */
    public String disabledReason() {
        return disabledReason;
    }

    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    public java.util.concurrent.atomic.AtomicLong cooldownUntilEpochMs() {
        return cooldownUntilEpochMs;
    }

    public AtomicInteger monthlyRemaining() {
        return monthlyRemaining;
    }

    public boolean isQuotaExhausted() {
        return quotaExhausted;
    }

    public void resetQuotaForMonthRollover(LocalDate today) {
        LocalDate effectiveToday = today == null ? LocalDate.now() : today;
        if (effectiveToday.getYear() == lastResetDate.getYear()
                && effectiveToday.getMonthValue() == lastResetDate.getMonthValue()) {
            return;
        }
        monthlyRemaining.set(Math.max(0, props.monthlyQuota()));
        quotaExhausted = false;
        lastResetDate = effectiveToday;
        clearOperationalDisableIfQuota();
    }

    public void markQuotaExhausted() {
        markQuotaExhaustedAndDisable("quota_exhausted");
    }

    public void clearQuotaExhausted() {
        quotaExhausted = false;
    }

    public QuotaReservation tryReserveFreeTierQuota() {
        resetQuotaForMonthRollover(LocalDate.now());
        if (props.monthlyQuota() <= 0) {
            return QuotaReservation.unmanaged();
        }
        if (!enabled || quotaExhausted) {
            traceFreeTierQuota(monthlyRemaining.get(), quotaExhausted, disabledReason);
            return QuotaReservation.denied();
        }

        while (true) {
            int current = monthlyRemaining.get();
            if (current <= 0) {
                markQuotaExhaustedAndDisable("quota_exhausted");
                return QuotaReservation.denied();
            }

            int next = current - 1;
            if (monthlyRemaining.compareAndSet(current, next)) {
                if (next <= 0) {
                    markQuotaExhaustedAndDisable("quota_exhausted");
                } else {
                    traceFreeTierQuota(next, false, "");
                }
                return QuotaReservation.granted();
            }
        }
    }

    public void completeFreeTierQuota(QuotaReservation reservation, HttpHeaders headers, boolean successfulResponse) {
        if (reservation == null || !reservation.reserved() || props.monthlyQuota() <= 0) {
            return;
        }

        Integer providerRemaining = parseMonthlyRemainingHeader(headers, "complete");
        if (providerRemaining != null && providerRemaining <= 0) {
            monthlyRemaining.set(0);
            markQuotaExhaustedAndDisable("quota_exhausted");
            return;
        }

        if (!successfulResponse) {
            releaseFreeTierQuota(reservation);
            return;
        }

        if (providerRemaining != null) {
            reconcileProviderRemaining(providerRemaining);
        } else {
            int remaining = monthlyRemaining.get();
            if (remaining <= 0) {
                markQuotaExhaustedAndDisable("quota_exhausted");
            } else {
                traceFreeTierQuota(remaining, false, "");
            }
        }
    }

    public void releaseFreeTierQuota(QuotaReservation reservation) {
        if (reservation == null || !reservation.reserved() || props.monthlyQuota() <= 0) {
            return;
        }
        int cap = Math.max(0, props.monthlyQuota());
        while (true) {
            int current = monthlyRemaining.get();
            int next = Math.min(cap, current + 1);
            if (monthlyRemaining.compareAndSet(current, next)) {
                if (next > 0 && isQuotaDisabledReason(disabledReason)) {
                    quotaExhausted = false;
                    clearOperationalDisableIfQuota();
                }
                traceFreeTierQuota(next, quotaExhausted, disabledReason);
                return;
            }
        }
    }

    public void reconcileProviderRemaining(long providerMonthlyRemaining) {
        if (props.monthlyQuota() <= 0) {
            return;
        }
        int bounded = (int) Math.min(Math.max(0L, providerMonthlyRemaining), (long) props.monthlyQuota());
        monthlyRemaining.updateAndGet(current -> Math.min(Math.max(0, current), bounded));
        int remaining = monthlyRemaining.get();
        if (remaining <= 0) {
            markQuotaExhaustedAndDisable("quota_exhausted");
        } else {
            traceFreeTierQuota(remaining, false, "");
        }
    }

    public void setOperationallyDisabled(String reason) {
        String current = disabledReason == null ? "" : disabledReason;
        if (current.startsWith("missing_api_key")
                || current.startsWith("missing BRAVE search token")
                || current.startsWith("disabled_by_config")) {
            return;
        }
        enabled = false;
        disabledReason = reason == null ? "disabled" : reason;
    }

    public void clearOperationalDisableIfQuota() {
        String current = disabledReason == null ? "" : disabledReason;
        if (isQuotaDisabledReason(current)) {
            enabled = true;
            disabledReason = "";
        }
    }

    public void applyRestTemplateTimeout(long timeoutMs) {
        long clamped = Math.max(200L, Math.min(60_000L, timeoutMs));
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) clamped);
        rf.setReadTimeout((int) clamped);
        restTemplate.setRequestFactory(rf);
    }

    public boolean addRestTemplateInterceptorIfAbsent(ClientHttpRequestInterceptor interceptor) {
        if (interceptor == null) return false;
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(restTemplate.getInterceptors());
        for (ClientHttpRequestInterceptor existing : interceptors) {
            if (existing != null && existing.getClass().equals(interceptor.getClass())) {
                return false;
            }
        }
        interceptors.add(0, interceptor);
        restTemplate.setInterceptors(interceptors);
        return true;
    }

    /**
     * Expose Brave cooldown state for upstream orchestration.
     * <p>
     * When Brave is rate-limited (HTTP 429), this service enters a cooldown window
     * and will short-circuit searches to empty. Upstream components may prefer to
     * skip scheduling Brave calls entirely to reduce thread & connection churn.
     */
    public boolean isCoolingDown() {
        long now = System.currentTimeMillis();
        return now < cooldownUntilEpochMs.get();
    }

    /** Remaining cooldown time (ms). Returns 0 if not cooling down. */
    public long cooldownRemainingMs() {
        long now = System.currentTimeMillis();
        long until = cooldownUntilEpochMs.get();
        long remaining = Math.max(0L, until - now);

        // Hard cap: guard against a prior bug or external drift that leaves cooldown far in the future.
        // This protects operations from long SKIP streaks that bias provider selection.
        if (remaining > MAX_429_COOLDOWN_MS) {
            long clampedUntil = now + MAX_429_COOLDOWN_MS;
            cooldownUntilEpochMs.set(clampedUntil);
            TraceStore.put("web.brave.cooldown.sanity.clamped", true);
            TraceStore.put("web.brave.cooldown.sanity.prevRemainingMs", remaining);
            TraceStore.put("web.brave.cooldown.sanity.newRemainingMs", MAX_429_COOLDOWN_MS);
            remaining = MAX_429_COOLDOWN_MS;
        }

        // "Stuck" guard: if time is moving but remaining is not decreasing (and until hasn't changed),
        // reset to the conservative cap to avoid infinite cooldown.
        long prevUntil = lastCooldownUntilObserved.getAndSet(until);
        long prevNow = lastCooldownCheckEpochMs.getAndSet(now);
        long prevRemaining = lastCooldownRemainingObserved.getAndSet(remaining);
        if (prevNow > 0L && until == prevUntil && (now - prevNow) > 1500L) {
            if (remaining >= prevRemaining) {
                long resetUntil = now + MAX_429_COOLDOWN_MS;
                cooldownUntilEpochMs.set(resetUntil);
                TraceStore.put("web.brave.cooldown.sanity.stuck", true);
                TraceStore.put("web.brave.cooldown.sanity.stuck.prevRemainingMs", prevRemaining);
                TraceStore.put("web.brave.cooldown.sanity.stuck.nowRemainingMs", remaining);
                remaining = MAX_429_COOLDOWN_MS;
            }
        }

        return remaining;
    }

    /**
     * Execute a Brave web search and return parsed snippets. Raw JSON is parsed
     * inside this service and should not be forwarded into LLM prompt context.
     */
    @Override
    @Cacheable(value = "webSearchCache", key = "#query + '-' + #topK", sync = true)
    public List<String> search(String query, int topK) {
        enterCacheableSection();
        try {
            return searchSnippets(query, topK);
        } finally {
            exitCacheableSection();
        }
    }


    /**
     * Cache-only escape hatch.
     *
     * <p>Reads from the same {@code webSearchCache} used by {@link #search(String, int)}
     * without invoking Brave's API. This is used by upstream fail-soft logic to avoid
     * empty-merge starvation when providers are rate-limited / breaker-open.</p>
     *
     * <p>Never triggers computation: if there is no cache entry it returns empty.</p>
     */
    public List<String> searchCacheOnly(String query, int topK) {
        try {
            if (cacheManager == null || query == null || query.isBlank() || topK <= 0) {
                return List.of();
            }
            Cache cache = cacheManager.getCache("webSearchCache");
            if (cache == null) {
                return List.of();
            }

            // Try the exact key first (must match @Cacheable key)
            List<String> hit = getCached(cache, query, topK);

            // Some callers clamp k to [5..20]; probe that too to improve hit-rate.
            int k2 = Math.min(Math.max(topK, 5), BRAVE_MAX_TOPK);
            if ((hit == null || hit.isEmpty()) && k2 != topK) {
                hit = getCached(cache, query, k2);
            }

            // Also probe the sanitized variant (cache keys are caller-provided).
            String safeQuery = sanitizeQuery(query);
            if ((hit == null || hit.isEmpty()) && safeQuery != null && !safeQuery.isBlank() && !safeQuery.equals(query)) {
                hit = getCached(cache, safeQuery, topK);
                if ((hit == null || hit.isEmpty()) && k2 != topK) {
                    hit = getCached(cache, safeQuery, k2);
                }
            }

            if (hit == null || hit.isEmpty()) {
                try {
                    TraceStore.putIfAbsent("web.brave.cacheOnly.miss", true);
                } catch (Throwable ignore) { TraceStore.put("web.brave.suppressed.cacheOnly.missTrace", true); BraveSearchTraceSuppressions.trace("cacheOnly.missTrace", ignore); }
                return List.of();
            }

            // Defensive: enforce limit and remove blanks
            java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>();
            for (String s : hit) {
                if (s == null || s.isBlank()) continue;
                dedup.add(s);
                if (dedup.size() >= topK) break;
            }
            List<String> out = new java.util.ArrayList<>(dedup);

            try {
                TraceStore.put("web.brave.cacheOnly.hit", true);
                TraceStore.put("web.brave.cacheOnly.hit.count", out.size());
                TraceStore.put("web.brave.cacheOnly.hit.topK", topK);
            } catch (Throwable ignore) { TraceStore.put("web.brave.suppressed.cacheOnly.hitTrace", true); BraveSearchTraceSuppressions.trace("cacheOnly.hitTrace", ignore); }

            return out;
        } catch (Throwable t) {
            try {
                TraceStore.putIfAbsent("web.brave.cacheOnly.error", "cache_only_failed");
            } catch (Throwable ignore) { TraceStore.put("web.brave.suppressed.cacheOnly.errorTrace", true); BraveSearchTraceSuppressions.trace("cacheOnly.errorTrace", ignore); }
            return List.of();
        }
    }

    private static List<String> getCached(Cache cache, String query, int topK) {
        try {
            String key = query + "-" + topK;
            Cache.ValueWrapper vw = cache.get(key);
            if (vw == null) {
                return null;
            }
            Object v = vw.get();
            if (v instanceof List<?> l) {
                java.util.ArrayList<String> out = new java.util.ArrayList<>(l.size());
                for (Object o : l) {
                    if (o == null) continue;
                    out.add(String.valueOf(o));
                }
                return out;
            }
            return null;
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.cacheOnly.getCached", true);
            return null;
        }
    }

    /**
     * Seed Brave results into the same "webSearchCache" used by @Cacheable search().
     *
     * <p>Why: some orchestration paths call {@link #searchWithMeta(String, int)} directly
     * (not the @Cacheable wrapper), causing cache-only rescue to miss and produce
     * empty-merge starvation.
     *
     * <p>We only seed when we have non-empty snippets, and we never overwrite an
     * existing non-empty cache entry.
     */
    private void seedWebSearchCache(String originalQuery, String safeQuery, int topK, List<String> snippets) {
        try {
            if (cacheManager == null || topK <= 0 || snippets == null || snippets.isEmpty()) {
                return;
            }

            // Avoid recursive cache updates when @Cacheable(sync=true) is computing this key.
            if (isInCacheableSection()) {
                try {
                    TraceStore.put("web.brave.cacheSeed.suppressed", true);
                    TraceStore.inc("web.brave.cacheSeed.suppressed.count");
                } catch (Throwable ignore) { TraceStore.put("web.brave.suppressed.cacheSeed.suppressedTrace", true); BraveSearchTraceSuppressions.trace("cacheSeed.suppressedTrace", ignore); }
                return;
            }
            Cache cache = cacheManager.getCache("webSearchCache");
            if (cache == null) {
                return;
            }

            // Clean & cap to improve stability of cached payloads.
            java.util.ArrayList<String> cleaned = new java.util.ArrayList<>();
            for (String s : snippets) {
                if (s == null || s.isBlank()) continue;
                cleaned.add(s);
                if (cleaned.size() >= topK) break;
            }
            if (cleaned.isEmpty()) {
                return;
            }

            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
            if (originalQuery != null && !originalQuery.isBlank()) {
                keys.add(originalQuery + "-" + topK);
            }
            if (safeQuery != null && !safeQuery.isBlank()) {
                keys.add(safeQuery + "-" + topK);
            }

            for (String key : keys) {
                if (key == null || key.isBlank()) continue;
                boolean hasNonEmpty = false;
                try {
                    Cache.ValueWrapper vw = cache.get(key);
                    if (vw != null) {
                        Object v = vw.get();
                        if (v instanceof List<?> l && !l.isEmpty()) {
                            hasNonEmpty = true;
                        }
                    }
                } catch (Throwable ignore) { TraceStore.put("web.brave.suppressed.cacheSeed.cacheRead", true); BraveSearchTraceSuppressions.trace("cacheSeed.cacheRead", ignore); }
                if (hasNonEmpty) {
                    continue;
                }
                try {
                    cache.put(key, cleaned);
                } catch (Throwable ignore) { TraceStore.put("web.brave.suppressed.cacheSeed.cacheWrite", true); BraveSearchTraceSuppressions.trace("cacheSeed.cacheWrite", ignore); }
            }

            try {
                TraceStore.put("web.brave.cacheSeeded", true);
                TraceStore.put("web.brave.cacheSeeded.count", cleaned.size());
                TraceStore.put("web.brave.cacheSeeded.topK", topK);
            } catch (Throwable ignore) { TraceStore.put("web.brave.suppressed.cacheSeed.seededTrace", true); BraveSearchTraceSuppressions.trace("cacheSeed.seededTrace", ignore); }
        } catch (Throwable ignore) { TraceStore.put("web.brave.suppressed.cacheSeed.outer", true); BraveSearchTraceSuppressions.trace("cacheSeed.outer", ignore); }
    }


    /**
     * Brave 검색을 메타 정보와 함께 수행한다.
     * - 기존 search()는 하위 호환을 위해 snippets만 반환 (cacheable)
     * - 이 메서드는 "0건"이 "실패"인지 "정상 0건"인지 구분 가능하도록 status를 포함한다.
     */
    public BraveSearchResult searchWithMeta(String query, int limit) {
        long t0 = System.nanoTime();
        return searchWithMetaInternal(query, limit, t0);
    }

    private BraveSearchResult searchWithMetaInternal(String query, int limit, long t0Ns) {
        BraveSearchResult base = searchWithMetaSingle(query, limit, System.nanoTime());
        int requestedTopK = (limit > 0 ? limit : 5);
        int topK = Math.min(requestedTopK, BRAVE_MAX_TOPK);
        List<String> baseSnippets = base == null || base.snippets() == null ? List.of() : base.snippets();

        if (!shouldRunBraveAdaptive(query, base)) {
            traceBraveAdaptiveSkipped(baseSnippets.isEmpty() ? "base-not-eligible" : "base-sufficient",
                    baseSnippets.size(), baseSnippets.size());
            return base;
        }

        String safeQuery = sanitizeQuery(query);
        AdaptiveSearchQueryVariants.Plan plan = planBraveVariants(safeQuery, List.of(safeQuery), true, false);
        List<String> plannedQueries = plan.queries();
        traceBraveAdaptive(plan, baseSnippets.size(), baseSnippets.size());

        if (plannedQueries.size() <= 1) {
            return base;
        }

        long deadlineNs = System.nanoTime() + Math.max(1L, plan.budgetMs()) * 1_000_000L;
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        for (String snippet : baseSnippets) {
            if (snippet == null || snippet.isBlank()) {
                continue;
            }
            merged.add(snippet);
            if (merged.size() >= topK) {
                break;
            }
        }

        long lastBraveCallNs = System.nanoTime();
        for (String variant : plannedQueries.subList(1, plannedQueries.size())) {
            if (merged.size() >= topK) {
                break;
            }
            long remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L;
            if (remainingMs < Math.max(1L, adaptivePerCallFloorMs)) {
                traceBraveAdaptiveSkipped("budget", merged.size(), merged.size());
                break;
            }

            if (!waitForBraveAdaptivePermitWindow(lastBraveCallNs, deadlineNs)) {
                traceBraveAdaptiveSkipped("budget", merged.size(), merged.size());
                break;
            }
            BraveSearchResult variantResult = searchWithMetaSingle(variant, topK, System.nanoTime());
            lastBraveCallNs = System.nanoTime();
            if (variantResult == null) {
                continue;
            }
            if (variantResult.status() == BraveSearchResult.Status.HTTP_429
                    || variantResult.status() == BraveSearchResult.Status.COOLDOWN
                    || variantResult.status() == BraveSearchResult.Status.RATE_LIMIT_LOCAL
                    || variantResult.status() == BraveSearchResult.Status.DISABLED) {
                traceBraveAdaptiveSkipped("provider-gated", merged.size(), merged.size());
                break;
            }
            if (variantResult.status() != BraveSearchResult.Status.OK) {
                continue;
            }
            for (String snippet : variantResult.snippets()) {
                if (snippet == null || snippet.isBlank()) {
                    continue;
                }
                merged.add(snippet);
                if (merged.size() >= topK) {
                    break;
                }
            }
        }

        List<String> snippets = new ArrayList<>(merged);
        if (!snippets.isEmpty()) {
            seedWebSearchCache(query, safeQuery, topK, snippets);
        }
        long elapsedMs = Math.max(0L, (System.nanoTime() - t0Ns) / 1_000_000L);
        traceBraveAdaptive(plan, snippets.size(), snippets.size());
        return BraveSearchResult.ok(snippets, elapsedMs);
    }

    private BraveSearchResult searchWithMetaSingle(String query, int limit, long t0Ns) {
        String safeQuery = sanitizeQuery(query);
        int requestedTopK = (limit > 0 ? limit : 5);
        int topK = Math.min(requestedTopK, BRAVE_MAX_TOPK);
        if (safeQuery.isBlank()) {
            // Downstream safety pin: explicit skip tag (do NOT call providers).
            logSkipOnce("SKIP_EMPTY_QUERY", "blank_query");
            traceBraveCounts(safeQuery, topK, 0, 0, false, "blank_query");
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.OK, 200, 0L, "blank query",
                    elapsedMs);
        }

        if (!enabled || quotaExhausted) {
            String safeDisabledReason = safeDisabledReason(disabledReason, "disabled");
            if (!enabled) {
                logSkipOnce("PROVIDER_DISABLED", safeDisabledReason);
                TraceStore.put("web.brave.providerDisabled", true);
                TraceStore.put("web.brave.disabledReason", safeDisabledReason);
            }
            traceBraveCounts(safeQuery, topK, 0, 0, true,
                    !enabled ? safeDisabledReason : "quota_exhausted");
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return BraveSearchResult.disabled(elapsedMs);
        }

        // Gate -1: cool-down after 429 to avoid retry storms.
        long now = System.currentTimeMillis();
        long cooldownUntil = cooldownUntilEpochMs.get();
        if (now < cooldownUntil) {
            traceBraveCounts(safeQuery, topK, 0, 0, false, "cooldown");
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return BraveSearchResult.cooldown(cooldownUntil - now, elapsedMs);
        }

        // Gate 0: Brave 비활성 / 로컬 월 쿼터 소진
        if (!enabled || quotaExhausted) {
            String safeDisabledReason = safeDisabledReason(disabledReason, "disabled");
            if (!enabled) {
                logSkipOnce("PROVIDER_DISABLED", safeDisabledReason);
                TraceStore.put("web.brave.providerDisabled", true);
                TraceStore.put("web.brave.disabledReason", safeDisabledReason);
            }
            traceBraveCounts(safeQuery, topK, 0, 0, true,
                    !enabled ? safeDisabledReason : "quota_exhausted");
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return BraveSearchResult.disabled(elapsedMs);
        }

        // Gate 1: 달 바뀌면 월 카운터 리셋
        LocalDate today = LocalDate.now();
        if (today.getYear() != lastResetDate.getYear()
                || !today.getMonth().equals(lastResetDate.getMonth())) {
            monthlyRemaining.set(props.monthlyQuota());
            quotaExhausted = false;
            lastResetDate = today;
        }

        // Gate 2: 로컬 월 쿼터
        if (props.monthlyQuota() > 0 && monthlyRemaining.get() <= 0) {
            markQuotaExhaustedAndDisable("quota_exhausted");

            // Treat quota exhaustion as a local "rate-limit" signal and apply a cooldown
            // to break tight retry loops (officialOnly / extraSearch can call Brave repeatedly).
            long cdMs = Math.min(MAX_429_COOLDOWN_MS, Math.max(5000L, props.cooldownMs()));
            startCooldown(cdMs);
            try {
                TraceStore.put("web.brave.quota.exhausted", true);
                TraceStore.put("web.brave.quota.cooldownMs", cdMs);
                TraceStore.put("web.brave.cooldown.reason", "quota_exhausted");
            } catch (Exception ignore) {
                TraceStore.put("web.brave.suppressed.quotaExhaustedTrace", true);
                // fail-soft
            }

            traceBraveCounts(safeQuery, topK, 0, 0, true, "quota_exhausted");
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.RATE_LIMIT_LOCAL, null, cdMs,
                    "local monthly quota exhausted", elapsedMs);
        }

        // Gate 3: QPS 레이트리미트
        if (!rateLimiter.tryAcquire(props.acquireTimeoutMs(), TimeUnit.MILLISECONDS)) {
            // IMPORTANT: cooldownMs must be >0 to avoid tight loops:
            // "skip -> immediate retry -> rate_limit_local" in the same session.
            long cdMs = localCooldownMsFor(effectiveQpsLimit);
            startCooldown(cdMs);
            try {
                TraceStore.put("web.brave.rate_limit_local", true);
                TraceStore.inc("web.brave.rate_limit_local.count");
                TraceStore.put("web.brave.rate_limit_local.cooldownMs", cdMs);
                TraceStore.put("web.brave.rate_limit_local.qps", effectiveQpsLimit);
                TraceStore.put("web.brave.rate_limit_local.acquireTimeoutMs", props.acquireTimeoutMs());
                TraceStore.put("web.brave.cooldown.reason", "rate_limit_local");
            } catch (Exception ignore) {
                TraceStore.put("web.brave.suppressed.rateLimitLocalTrace", true);
                // fail-soft
            }

            traceBraveCounts(safeQuery, topK, 0, 0, false, "rate_limit_local");
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.RATE_LIMIT_LOCAL, null, cdMs,
                    "local rate limit hit", elapsedMs);
        } else {
            // Success: reset local streak so cooldown doesn't grow unnecessarily.
            try {
                localRateLimitStreak.set(0);
            } catch (Throwable ignore) {
                TraceStore.put("web.brave.suppressed.localStreakReset", true);
                // best-effort
            }
        }

        QuotaReservation quotaReservation = tryReserveFreeTierQuota();
        if (!quotaReservation.allowed()) {
            long cdMs = Math.min(MAX_429_COOLDOWN_MS, Math.max(5000L, props.cooldownMs()));
            startCooldown(cdMs);
            traceBraveCounts(safeQuery, topK, 0, 0, true, "quota_exhausted");
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.RATE_LIMIT_LOCAL, null, cdMs,
                    "local monthly quota exhausted", elapsedMs);
        }

        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .queryParam("q", safeQuery)
                    .queryParam("count", topK)
                    .build()
                    .encode(StandardCharsets.UTF_8)
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Subscription-Token", apiKey);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            // Correlation IDs for observability (best-effort).
            try {
                String rid = MDC.get(LogCorrelation.KEY_REQUEST_ID);
                if (rid != null && !rid.isBlank()) {
                    headers.set("x-request-id", rid);
                }
                String sid = MDC.get(LogCorrelation.KEY_SESSION_ID);
                if (sid != null && !sid.isBlank()) {
                    headers.set("x-session-id", sid);
                }
            } catch (Throwable ignore) {
                TraceStore.put("web.brave.suppressed.correlationHeaders", true);
                // best-effort
            }
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> res = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
            String body = res.getBody();
            if (body == null || body.isBlank()) {
                completeFreeTierQuota(quotaReservation, res.getHeaders(), true);
                traceBraveCounts(safeQuery, topK, 0, 0, false, null);
                long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
                return BraveSearchResult.ok(java.util.List.of(), elapsedMs);
            }

            List<String> snippets = extractSnippetsFromJson(body, topK);
            if (snippets == null) {
                snippets = java.util.List.of();
            }
            traceBraveCounts(safeQuery, topK, snippets.size(), snippets.size(), false, null);
            if (snippets.isEmpty()) {
                log.debug("[AWX2AF2][search][brave] zero results returnedCount=0 requestedCount={} timeoutMs={}",
                        topK, timeoutMs);
            }

            
            // Seed cache for cache-only rescue paths (searchWithMeta is not @Cacheable).
            seedWebSearchCache(query, safeQuery, topK, snippets);

            completeFreeTierQuota(quotaReservation, res.getHeaders(), true);
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return BraveSearchResult.ok(snippets, elapsedMs);

        } catch (HttpClientErrorException.TooManyRequests e) {
            TraceStore.put("web.brave.suppressed.http429", true);
            long retryAfterMs = retryAfterToMs(e.getResponseHeaders());
            long cooldownMs = Math.max(Math.max(0L, props.cooldownMs()), retryAfterMs);
            startCooldown(cooldownMs);
            traceRemoteCooldown("rate-limit", retryAfterMs, cooldownMs);
            completeFreeTierQuota(quotaReservation, e.getResponseHeaders(), false);

            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            traceBraveFailure(safeQuery, topK, 429, "rate-limit", true, false,
                    e.getResponseBodyAsString(), elapsedMs);
            String msg = (retryAfterMs > 0) ? ("remote 429 retryAfterMs=" + retryAfterMs) : "remote 429";
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.HTTP_429, 429, cooldownMs,
                    msg, elapsedMs);
        } catch (HttpStatusCodeException e) {
            TraceStore.put("web.brave.suppressed.httpStatus", true);
            int code = (e.getStatusCode() != null) ? e.getStatusCode().value() : -1;
            long retryAfterMs = retryAfterToMs(e.getResponseHeaders());
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;

            // Brave may return 503 with a Retry-After hint. Treat this as transient backoff
            // (not a hard failure) so officialOnly does not collapse into starvation.
            if (code == 503) {
                // 503 is often short-lived; default to a small cooldown unless server demands longer.
                long base = Math.max(0L, props.cooldownMs());
                long defaultCd = Math.min(base, 5000L);
                long cooldownMs = Math.max(retryAfterMs, defaultCd);

                if (cooldownMs > 0) {
                    startCooldown(cooldownMs);
                }
                traceRemoteCooldown("http-503", retryAfterMs, cooldownMs);
                completeFreeTierQuota(quotaReservation, e.getResponseHeaders(), false);

                traceBraveFailure(safeQuery, topK, 503, "http-503", false, false,
                        e.getResponseBodyAsString(), elapsedMs);
                String msg = (retryAfterMs > 0) ? ("remote 503 retryAfterMs=" + retryAfterMs) : "remote 503";
                return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.HTTP_503, 503, cooldownMs,
                        msg, elapsedMs);
            }

            // For other HTTP errors, preserve existing behavior (no local cooldown)
            completeFreeTierQuota(quotaReservation, e.getResponseHeaders(), false);
            traceBraveFailure(safeQuery, topK, code, "http-error", code == 429, false,
                    e.getResponseBodyAsString(), elapsedMs);
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.HTTP_ERROR,
                    (code > 0 ? code : null), 0L, e.getStatusText(), elapsedMs);
        } catch (ResourceAccessException e) {
            TraceStore.put("web.brave.suppressed.resourceAccess", true);
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            boolean cancelled = isCancellationFailure(e);
            traceBraveFailure(safeQuery, topK, -1, cancelled ? "cancelled" : "timeout", false, !cancelled, null, elapsedMs);
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.EXCEPTION,
                    null, 0L, cancelled ? "cancelled" : "timeout", elapsedMs);
        } catch (Exception e) {
            TraceStore.put("web.brave.suppressed.exception", true);
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            boolean cancelled = isCancellationFailure(e);
            boolean timeout = !cancelled && isTimeoutFailure(e);
            String reason = cancelled ? "cancelled" : timeout ? "timeout" : "exception";
            traceBraveFailure(safeQuery, topK, -1, reason, false, timeout, null, elapsedMs);
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.EXCEPTION,
                    null, 0L, reason, elapsedMs);
        }
    }

    private boolean shouldRunBraveAdaptive(String query, BraveSearchResult base) {
        if (!adaptiveSearchEnabled || base == null) {
            return false;
        }
        if (base.status() != BraveSearchResult.Status.OK) {
            return false;
        }
        List<String> snippets = base.snippets() == null ? List.of() : base.snippets();
        return snippets.isEmpty() && !sanitizeQuery(query).isBlank();
    }

    private boolean waitForBraveAdaptivePermitWindow(long lastCallNs, long deadlineNs) {
        if (effectiveQpsLimit <= 0.0d) {
            return true;
        }
        long minGapMs = (long) Math.ceil(1000.0d / effectiveQpsLimit);
        long elapsedSinceLastMs = Math.max(0L, (System.nanoTime() - lastCallNs) / 1_000_000L);
        long waitMs = Math.max(0L, minGapMs - elapsedSinceLastMs);
        if (waitMs <= 0L) {
            return true;
        }
        long remainingMs = Math.max(0L, (deadlineNs - System.nanoTime()) / 1_000_000L);
        if (remainingMs <= waitMs + Math.max(1L, adaptivePerCallFloorMs)) {
            return false;
        }
        try {
            Thread.sleep(waitMs);
            return true;
        } catch (InterruptedException e) {
            TraceStore.put("web.brave.suppressed.adaptiveInterrupt", true);
            Thread.currentThread().interrupt();
            traceBraveAdaptiveSkipped("interrupted", 0, 0);
            return false;
        }
    }

    private AdaptiveSearchQueryVariants.Plan planBraveVariants(String originalQuery,
                                                               List<String> baseQueries,
                                                               boolean providerEmpty,
                                                               boolean afterFilterStarved) {
        long maxOverallMs = Math.max(adaptivePerCallFloorMs, adaptiveMaxOverallTimeoutMs);
        return AdaptiveSearchQueryVariants.plan(
                originalQuery,
                baseQueries,
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.BRAVE,
                        adaptiveSearchEnabled,
                        Math.max(1, Math.min(3, adaptiveMaxQueries)),
                        maxOverallMs,
                        maxOverallMs,
                        Math.max(1L, adaptivePerCallFloorMs),
                        false,
                        providerEmpty,
                        afterFilterStarved));
    }

    private void traceBraveAdaptive(AdaptiveSearchQueryVariants.Plan plan, int returnedCount, int afterFilterCount) {
        try {
            if (plan == null) {
                return;
            }
            TraceStore.put("web.brave.adaptive.enabled", plan.enabled());
            TraceStore.put("web.brave.adaptive.triggerReason", SafeRedactor.traceLabelOrFallback(plan.triggerReason(), "unknown"));
            TraceStore.put("web.brave.adaptive.variantCount", Math.max(0, plan.queries().size() - 1));
            TraceStore.put("web.brave.adaptive.sliceCount", Math.max(0, plan.sliceCount()));
            TraceStore.put("web.brave.adaptive.expansionCount", Math.max(0, plan.expansionCount()));
            TraceStore.put("web.brave.adaptive.budgetMs", Math.max(0L, plan.budgetMs()));
            TraceStore.put("web.brave.adaptive.perCallMs", Math.max(0L, plan.perCallMs()));
            TraceStore.put("web.brave.adaptive.returnedCount", Math.max(0, returnedCount));
            TraceStore.put("web.brave.adaptive.afterFilterCount", Math.max(0, afterFilterCount));
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.adaptiveTrace", true);
            // fail-soft telemetry only
        }
    }

    private void traceBraveAdaptiveSkipped(String reason, int returnedCount, int afterFilterCount) {
        try {
            TraceStore.put("web.brave.adaptive.enabled", adaptiveSearchEnabled);
            TraceStore.put("web.brave.adaptive.triggerReason", SafeRedactor.traceLabelOrFallback(reason == null || reason.isBlank() ? "skipped" : reason, "skipped"));
            TraceStore.put("web.brave.adaptive.variantCount", 0);
            TraceStore.put("web.brave.adaptive.sliceCount", 0);
            TraceStore.put("web.brave.adaptive.expansionCount", 0);
            TraceStore.put("web.brave.adaptive.budgetMs", Math.max(0L, adaptiveMaxOverallTimeoutMs));
            TraceStore.put("web.brave.adaptive.perCallMs", Math.max(0L, adaptivePerCallFloorMs));
            TraceStore.put("web.brave.adaptive.returnedCount", Math.max(0, returnedCount));
            TraceStore.put("web.brave.adaptive.afterFilterCount", Math.max(0, afterFilterCount));
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.adaptiveSkippedTrace", true);
            // fail-soft telemetry only
        }
    }

    private void traceBraveCounts(String query,
                                  int requestedCount,
                                  int returnedCount,
                                  int afterFilterCount,
                                  boolean providerDisabled,
                                  String disabledReason) {
        try {
            int requested = Math.max(0, requestedCount);
            int returned = Math.max(0, returnedCount);
            int after = Math.max(0, afterFilterCount);
            String skipReason = !providerDisabled && disabledReason != null && !disabledReason.isBlank()
                    ? SafeRedactor.traceLabelOrFallback(disabledReason, "unknown")
                    : "";
            TraceStore.put("web.brave.requestedCount", requested);
            TraceStore.put("web.brave.timeoutMs", Math.max(0, timeoutMs));
            TraceStore.put("web.brave.returnedCount", returned);
            TraceStore.put("web.brave.afterFilterCount", after);
            TraceStore.put("web.brave.zeroResults", returned == 0);
            TraceStore.put("web.brave.afterFilterStarved", returned > 0 && after == 0);
            TraceStore.put("web.brave.providerDisabled", providerDisabled);
            TraceStore.put("web.brave.providerEmpty", !providerDisabled && skipReason.isBlank() && returned == 0);
            resetProviderHttpFailureFlags();
            TraceStore.put("web.brave.timeout", false);
            TraceStore.put("web.brave.cancelled", false);
            if (query != null && !query.isBlank()) {
                TraceStore.put("web.brave.queryHash", SafeRedactor.hashValue(query));
            }
            TraceStore.put("web.brave.queryLength", query == null ? 0 : query.length());
            TraceStore.put("web.brave.queryTokenBucket", queryTokenBucket(query));
            if (!skipReason.isBlank()) {
                TraceStore.put("web.brave.skipped.reason", skipReason);
            }
            TraceStore.put("web.brave.failureReason", !skipReason.isBlank()
                    ? skipReason
                    : classifyBraveCountFailure(returned, after, providerDisabled));
            if (providerDisabled) {
                String reason = disabledReason == null || disabledReason.isBlank()
                        ? "disabled"
                        : disabledReason;
                String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
                TraceStore.put("web.brave.providerDisabled", true);
                TraceStore.put("web.brave.disabledReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
                TraceStore.put("web.brave.disabledReasonCanonical", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
                TraceStore.put("web.brave.skipped", true);
                TraceStore.put("web.brave.skipped.reason", safeReason);
                TraceStore.putIfAbsent("web.brave.skipped.stage", "provider_disabled");
            }
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.countsTrace", true);
            // fail-soft telemetry only
        }
    }

    private static String classifyBraveCountFailure(int returned, int after, boolean providerDisabled) {
        if (providerDisabled) {
            return "provider-disabled";
        }
        if (returned <= 0) {
            return "provider-empty";
        }
        if (after <= 0) {
            return "after-filter-starvation";
        }
        return "none";
    }

    private void traceBraveFailure(String query,
                                   int requestedCount,
                                   int httpStatus,
                                   String failureReason,
                                   boolean rateLimited,
                                   boolean timeout,
                                   String errorBody,
                                   long tookMs) {
        try {
            traceBraveCounts(query, requestedCount, 0, 0, false, null);
            TraceStore.put("web.brave.providerEmpty", false);
            if (httpStatus > 0) {
                TraceStore.put("web.brave.httpStatus", httpStatus);
            }
            TraceStore.put("web.brave.429", httpStatus == 429);
            TraceStore.put("web.brave.rateLimited", rateLimited);
            TraceStore.put("web.brave.timeout", timeout);
            String safeFailureReason = SafeRedactor.traceLabelOrFallback(failureReason == null ? "unknown" : failureReason, "unknown");
            TraceStore.put("web.brave.failureReason", safeFailureReason);
            boolean cancelled = "cancelled".equals(safeFailureReason);
            TraceStore.put("web.brave.cancelled", cancelled);
            if (cancelled) {
                TraceStore.put("web.brave.exceptionType", "cancelled");
            }
            TraceStore.put("web.brave.tookMs", Math.max(0L, tookMs));
            if (rateLimited) {
                TraceStore.put("web.rateLimited", true);
            }
            traceErrorBody("web.brave", errorBody, query);
            log.warn("[AWX][search][brave] provider failure failureReason={} httpStatus={} queryHash={} queryLength={} bodyHash={} bodyLength={} tookMs={}",
                    safeFailureReason,
                    httpStatus > 0 ? httpStatus : "none",
                    query == null ? "" : SafeRedactor.hashValue(query),
                    query == null ? 0 : query.length(),
                    errorBody == null || errorBody.isBlank() ? "" : SafeRedactor.hashValue(errorBody),
                    errorBody == null ? 0 : errorBody.length(),
                    Math.max(0L, tookMs));
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.failureTrace", true);
            // fail-soft telemetry only
        }
    }

    private static void traceErrorBody(String prefix, String body, String query) {
        TraceStore.put(prefix + ".errorBodyLength", body == null ? 0 : body.length());
        if (body == null || body.isBlank()) {
            return;
        }
        TraceStore.put(prefix + ".errorBodyHash", SafeRedactor.hashValue(body));
    }

    private static void resetProviderHttpFailureFlags() {
        TraceStore.put("web.brave.httpStatus", null);
        TraceStore.put("web.brave.429", false);
        TraceStore.put("web.brave.rateLimited", false);
    }

    private static boolean isTimeoutFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof ResourceAccessException) {
                return true;
            }
            String type = cur.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (type.contains("timeout") || msg.contains("timeout") || msg.contains("timed out")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean isCancellationFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (msg.contains("cancelled") || msg.contains("canceled")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String queryTokenBucket(String query) {
        if (query == null || query.isBlank()) {
            return "0";
        }
        int tokens = query.trim().split("\\s+").length;
        if (tokens <= 4) {
            return "1-4";
        }
        if (tokens <= 12) {
            return "5-12";
        }
        if (tokens <= 32) {
            return "13-32";
        }
        return "33+";
    }

    private static String safeDisabledReason(String reason, String fallback) {
        String value = reason == null || reason.isBlank() ? fallback : reason;
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }

    private void logSkipOnce(String tag, String reason) {
        try {
            String k = "brave." + tag;
            if (TraceStore.get(k) != null) return;
            TraceStore.put(k, "1");
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.logSkipOnce", true);
            // ignore
        }
        log.info("[{}] Brave call skipped (reason={}){}", tag, SafeRedactor.traceLabelOrFallback(reason, "unknown"), LogCorrelation.suffix());
    }

    public List<String> searchSnippets(String query, int limit) {
        BraveSearchResult r = searchWithMeta(query, limit);
        if (r == null || r.snippets() == null) {
            return Collections.emptyList();
        }
        return r.snippets();
    }

    /**
     * Parse Brave Web Search JSON and extract human-readable snippets.
     * This keeps raw JSON out of the LLM context and is resilient to minor schema
     * changes.
     */
    private java.util.List<String> extractSnippetsFromJson(String json, int topK) {
        java.util.List<String> snippets = new java.util.ArrayList<>();

        // Primary: Jackson parsing
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode results = root.path("web").path("results");

            if (results.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : results) {
                    if (snippets.size() >= topK)
                        break;

                    String title = node.path("title").asText("");
                    String desc = node.path("description").asText("");
                    String url = node.path("url").asText("");

                    StringBuilder sb = new StringBuilder();
                    if (!title.isBlank()) {
                        sb.append(title.trim()).append("\n");
                    }
                    if (!desc.isBlank()) {
                        sb.append(desc.trim()).append("\n");
                    }
                    if (!url.isBlank()) {
                        sb.append("URL: ").append(url.trim());
                    }

                    String snippet = sb.toString().trim();
                    if (!snippet.isBlank()) {
                        snippets.add(snippet);
                    }
                }
            }

            log.debug("[Brave] Parsed {} snippets from JSON", snippets.size());
            return snippets;

        } catch (Exception e) {
            TraceStore.put("web.brave.suppressed.jsonParse", true);
            log.warn("[AWX][search][brave] parse failed failureReason={} errorType={} bodyHash={} bodyLength={}",
                    "json-parse-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hashValue(json),
                    json == null ? 0 : json.length());
        }

        // Fallback: Regex extraction on raw JSON
        try {
            Pattern pattern = Pattern.compile("\"description\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(json);

            while (matcher.find() && snippets.size() < topK) {
                String desc = matcher.group(1)
                        .replace("\\n", " ")
                        .replace("\\\"", "\"");
                if (!desc.isBlank()) {
                    snippets.add(desc);
                }
            }

            if (!snippets.isEmpty()) {
                log.debug("[Brave] Regex fallback extracted {} snippets", snippets.size());
                return snippets;
            }
        } catch (Exception e) {
            TraceStore.put("web.brave.suppressed.regexFallback", true);
            log.error("[AWX][search][brave] regex fallback failed failureReason={} errorType={} bodyHash={} bodyLength={}",
                    "regex-fallback-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hashValue(json),
                    json == null ? 0 : json.length());
        }

        // Ultimate fallback: empty list (never return raw JSON as snippet)
        return java.util.Collections.emptyList();
    }

    /**
     * Local backoff used when the in-process QPS limiter is hit (RATE_LIMIT_LOCAL).
     * <p>
     * Goal: break "skip -> immediate retry -> rate_limit_local" tight loops while keeping
     * added latency small (short cooldown by default, but operator-tunable).
     * </p>
     */
    private long localCooldownMsFor(double qps) {
        long base = 0L;
        try {
            base = Math.max(0L, props.localCooldownMs());
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.localCooldownProps", true);
            base = 0L;
        }

        double effectiveQps = qps;
        if (effectiveQps <= 0.0d || Double.isNaN(effectiveQps) || Double.isInfinite(effectiveQps)) {
            effectiveQps = 1.0d;
        }

        // Approx: use 25% of the QPS interval as a short local backoff (ex: 0.8 qps -> ~313ms),
        // but never below 50ms. This breaks retry tight loops without blocking too long.
        long intervalMs = (long) Math.ceil(1000.0d / Math.max(0.05d, effectiveQps));
        long derivedMs = (long) Math.ceil(intervalMs * 0.25d);

        long baseMs = Math.max(base, Math.max(50L, derivedMs));

        // Exponential backoff (capped) + small jitter.
        long now = System.currentTimeMillis();
        int streak = 1;
        try {
            long last = lastLocalRateLimitAtEpochMs.getAndSet(now);
            // Decay the streak after a short quiet period so we recover quickly.
            if (last > 0L && (now - last) > 5_000L) {
                localRateLimitStreak.set(0);
            }
            streak = Math.max(1, localRateLimitStreak.incrementAndGet());
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.localCooldownStreak", true);
            streak = 1;
        }

        int capped = Math.min(Math.max(1, streak), 6);
        long backoff = baseMs * (1L << (capped - 1));

        long cap = Math.max(MAX_LOCAL_COOLDOWN_MS, baseMs); // allow operator override + qps-derived raise
        if (cap > MAX_429_COOLDOWN_MS) {
            cap = MAX_429_COOLDOWN_MS;
        }

        long jitter = 0L;
        try {
            long jitterCap = Math.min(250L, Math.max(0L, baseMs / 3));
            if (jitterCap > 0L) {
                jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0L, jitterCap + 1L);
            }
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.localCooldownJitter", true);
            jitter = 0L;
        }

        long out = Math.min(backoff + jitter, cap);
        if (out <= 0L) {
            out = Math.min(cap, Math.max(1L, baseMs));
        }

        try {
            TraceStore.put("web.brave.rate_limit_local.streak", streak);
            TraceStore.put("web.brave.rate_limit_local.baseMs", baseMs);
            TraceStore.put("web.brave.rate_limit_local.jitterMs", jitter);
            TraceStore.put("web.brave.rate_limit_local.capMs", cap);
        } catch (Exception ignore) {
            TraceStore.put("web.brave.suppressed.localCooldownTrace", true);
            // best-effort
        }
        return out;
    }


    // ---------------------------------------------------------------------
    // [Fix] Rate-limit / 429 handling helpers
    // ---------------------------------------------------------------------
    private void markQuotaExhaustedAndDisable(String reason) {
        quotaExhausted = true;
        monthlyRemaining.updateAndGet(v -> Math.max(0, v));
        setOperationallyDisabled(reason == null || reason.isBlank() ? "quota_exhausted" : reason);
        long cdMs = Math.min(MAX_429_COOLDOWN_MS, Math.max(5000L, props.cooldownMs()));
        startCooldown(cdMs);
        traceFreeTierQuota(monthlyRemaining.get(), true, disabledReason);
        try {
            String safeDisabledReason = safeDisabledReason(disabledReason, "quota_exhausted");
            TraceStore.put("web.brave.quota.exhausted", true);
            TraceStore.put("web.brave.quota.cooldownMs", cdMs);
            TraceStore.put("web.brave.cooldown.reason", "quota_exhausted");
            TraceStore.put("web.brave.providerDisabled", true);
            TraceStore.put("web.brave.disabledReason", safeDisabledReason);
            TraceStore.put("web.brave.disabledReasonCanonical", safeDisabledReason);
        } catch (Exception ignore) {
            TraceStore.put("web.brave.suppressed.markQuotaTrace", true);
            // fail-soft telemetry only
        }
    }

    private static boolean isQuotaDisabledReason(String reason) {
        return reason != null && reason.startsWith("quota_exhausted");
    }

    private void traceFreeTierQuota(int remaining, boolean exhausted, String reason) {
        try {
            TraceStore.put("web.brave.quota.monthlyQuota", Math.max(0, props.monthlyQuota()));
            TraceStore.put("web.brave.quota.remaining", Math.max(0, remaining));
            TraceStore.put("web.brave.quota.exhausted", exhausted);
            if (reason != null && !reason.isBlank()) {
                TraceStore.put("web.brave.quota.disabledReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            }
        } catch (Exception ignore) {
            TraceStore.put("web.brave.suppressed.freeTierQuotaTrace", true);
            // fail-soft telemetry only
        }
    }

    private Integer parseMonthlyRemainingHeader(HttpHeaders headers, String context) {
        if (headers == null) {
            return null;
        }
        String remainingHeader = headers.getFirst("X-RateLimit-Remaining");
        if (remainingHeader == null || remainingHeader.isBlank()) {
            return null;
        }
        try {
            String[] parts = remainingHeader.split(",");
            String candidate = parts[parts.length - 1].trim();
            return Integer.parseInt(candidate);
        } catch (NumberFormatException e) {
            TraceStore.put("web.brave.suppressed.parseMonthlyRemaining", true);
            TraceStore.put("web.brave.suppressed.parseMonthlyRemaining.errorType", "invalid_number");
            log.warn("[Brave] Failed to parse X-RateLimit-Remaining on {} errorType={} headerHash={} headerLength={}",
                    SafeRedactor.traceLabelOrFallback(context, "unknown"),
                    "invalid_number",
                    SafeRedactor.hashValue(remainingHeader),
                    remainingHeader.length());
            return null;
        }
    }

    private void startCooldown(long waitMs) {
        long now = System.currentTimeMillis();
        long ms = waitMs <= 0 ? DEFAULT_429_COOLDOWN_MS : waitMs;
        if (ms > MAX_429_COOLDOWN_MS) {
            ms = MAX_429_COOLDOWN_MS;
        }

        long requestedUntil = now + ms;
        long hardCapUntil = now + MAX_429_COOLDOWN_MS;

        long appliedUntil = cooldownUntilEpochMs.updateAndGet(prev -> {
            // prev may already be far in the future due to repeated 429s or clock anomalies.
            // Clamp it first so we don't get stuck skipping Brave for an unbounded period.
            long prevClamped = Math.min(prev, hardCapUntil);
            long candidate = Math.max(prevClamped, requestedUntil);
            return Math.min(candidate, hardCapUntil);
        });

        // keep ops visibility without spamming structured logs
        try {
            long appliedMs = Math.max(0L, appliedUntil - now);
            TraceStore.put("web.brave.cooldownMs", appliedMs);
            if (appliedUntil != requestedUntil) {
                TraceStore.put("web.brave.cooldown.adjusted", true);
            }
        } catch (Exception ignore) {
            TraceStore.put("web.brave.suppressed.startCooldownTrace", true);
            // ignore
        }
    }

    private static void traceRemoteCooldown(String reason, long retryAfterMs, long cooldownMs) {
        try {
            TraceStore.put("web.brave.cooldown.reason",
                    SafeRedactor.traceLabelOrFallback(reason == null || reason.isBlank() ? "remote" : reason, "remote"));
            TraceStore.put("web.brave.retryAfterMs", Math.max(0L, retryAfterMs));
            TraceStore.put("web.brave.cooldown.hintMs", Math.max(0L, cooldownMs));
        } catch (Throwable ignore) {
            TraceStore.put("web.brave.suppressed.remoteCooldownTrace", true);
            // fail-soft telemetry only
        }
    }

    private static long retryAfterToMs(HttpHeaders headers) {
        if (headers == null) {
            return DEFAULT_429_COOLDOWN_MS;
        }
        String retryAfter = headers.getFirst("Retry-After");
        if (retryAfter == null || retryAfter.isBlank()) {
            return DEFAULT_429_COOLDOWN_MS;
        }
        String v = retryAfter.trim();

        // Retry-After can be either delay-seconds or an HTTP-date.
        // 1) delay-seconds
        try {
            long sec = Long.parseLong(v);
            if (sec <= 0) {
                return DEFAULT_429_COOLDOWN_MS;
            }
            long ms = sec * 1000L;
            if (ms > MAX_429_COOLDOWN_MS) {
                ms = MAX_429_COOLDOWN_MS;
            }
            return ms;
        } catch (NumberFormatException ignore) {
            TraceStore.put("web.brave.suppressed.retryAfterSecondsParse", true);
            TraceStore.put("web.brave.suppressed.retryAfterSecondsParse.errorType", "invalid_number");
            // fallthrough to HTTP-date
        }

        // 2) HTTP-date (RFC_1123_DATE_TIME)
        try {
            ZonedDateTime dt = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            long deltaMs = dt.toInstant().toEpochMilli() - System.currentTimeMillis();
            if (deltaMs <= 0) {
                return DEFAULT_429_COOLDOWN_MS;
            }
            if (deltaMs > MAX_429_COOLDOWN_MS) {
                deltaMs = MAX_429_COOLDOWN_MS;
            }
            return deltaMs;
        } catch (java.time.DateTimeException ignore) {
            TraceStore.put("web.brave.suppressed.retryAfterDateParse", true);
            TraceStore.put("web.brave.suppressed.retryAfterDateParse.errorType", "invalid_date");
            return DEFAULT_429_COOLDOWN_MS;
        }
    }

    /**
     * On error responses (e.g. 429) Brave might still include rate-limit headers.
     * We update the local quota tracker only when the header is present.
     * (Unlike the success-path, we do NOT decrement when missing.)
     */
    private void updateQuotaFromHeadersOnError(HttpHeaders headers) {
        Integer providerRemaining = parseMonthlyRemainingHeader(headers, "error");
        if (providerRemaining != null) {
            reconcileProviderRemaining(providerRemaining);
        }
    }

    private void updateQuotaFromHeaders(HttpHeaders headers) {
        Integer providerRemaining = parseMonthlyRemainingHeader(headers, "success");
        if (providerRemaining != null) {
            reconcileProviderRemaining(providerRemaining);
            return;
        }

        // 헤더에 남은 쿼터 정보가 없으면 보수적으로 1 감소
    }

    @Override
    public SearchResult searchWithTrace(String query, int topK) {
        List<String> snippets = search(query, topK);
        return new SearchResult(snippets, null);
    }

    @Override
    public String getName() {
        return "Brave";
    }

    @Override
    public boolean supportsSiteOrSyntax() {
        // Brave Search typically supports boolean OR and site: filters.
        return true;
    }

    @Override
    public boolean isAvailable() {
        return isEnabled();
    }

    @Override
    public int getPriority() {
        return 20;
    }
}
