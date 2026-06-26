package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.trace.LogCorrelation;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ProviderRateLimitBackoffAspect {

    private static final Logger log = LoggerFactory.getLogger(ProviderRateLimitBackoffAspect.class);

    private final RateLimitBackoffCoordinator backoff;

    public ProviderRateLimitBackoffAspect(RateLimitBackoffCoordinator backoff) {
        this.backoff = backoff;
    }

    @Around("execution(* com.example.lms.service.NaverSearchService.searchSnippetsSync(..))")
    public Object aroundNaverSearchSnippetsSync(ProceedingJoinPoint pjp) throws Throwable {
        RateLimitBackoffCoordinator.Decision d = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);
        if (d.shouldSkip()) {
            markSkipped("naver", d);
            return List.of();
        }

        try {
            Object out = pjp.proceed();
            if (isTrueish(TraceStore.get("web.naver.429")) || isTrueish(TraceStore.get("web.rateLimited"))) {
                backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, null,
                        "naver_trace_429", null);
                TraceStore.put("web.naver.cooldown.startedNow", true);
            } else if (out instanceof List<?> l && !l.isEmpty()) {
                backoff.recordSuccess(RateLimitBackoffCoordinator.PROVIDER_NAVER);
            }
            return out;
        } catch (Throwable t) {
            if (isRateLimit429(t)) {
                Long retryAfterMs = extractRetryAfterMs(t);
                backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, retryAfterMs,
                        "naver_exception_429", extractRetryAfterHeader(t));
                TraceStore.put("web.naver.cooldown.startedNow", true);
                markRateLimited("naver", retryAfterMs, t);
                return List.of();
            }
            throw t;
        }
    }

    @Around("execution(* com.example.lms.service.NaverSearchService.searchWithTraceSync(..))")
    public Object aroundNaverSearchWithTraceSync(ProceedingJoinPoint pjp) throws Throwable {
        RateLimitBackoffCoordinator.Decision d = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);
        if (d.shouldSkip()) {
            markSkipped("naver", d);
            return new NaverSearchService.SearchResult(List.of(), new NaverSearchService.SearchTrace());
        }

        try {
            Object out = pjp.proceed();
            if (isTrueish(TraceStore.get("web.naver.429")) || isTrueish(TraceStore.get("web.rateLimited"))) {
                backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, null,
                        "naver_trace_429", null);
                TraceStore.put("web.naver.cooldown.startedNow", true);
            } else if (out instanceof NaverSearchService.SearchResult sr
                    && sr.snippets() != null && !sr.snippets().isEmpty()) {
                backoff.recordSuccess(RateLimitBackoffCoordinator.PROVIDER_NAVER);
            }
            return out;
        } catch (Throwable t) {
            if (isRateLimit429(t)) {
                Long retryAfterMs = extractRetryAfterMs(t);
                backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, retryAfterMs,
                        "naver_exception_429", extractRetryAfterHeader(t));
                TraceStore.put("web.naver.cooldown.startedNow", true);
                markRateLimited("naver", retryAfterMs, t);
                return new NaverSearchService.SearchResult(List.of(), new NaverSearchService.SearchTrace());
            }
            throw t;
        }
    }

    @Around("execution(* com.example.lms.service.web.BraveSearchService.searchWithMeta(..))")
    public Object aroundBraveSearchWithMeta(ProceedingJoinPoint pjp) throws Throwable {
        RateLimitBackoffCoordinator.Decision d = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_BRAVE);
        if (d.shouldSkip()) {
            markSkipped("brave", d);
            return BraveSearchResult.cooldown(d.remainingMs(), 0L);
        }

        try {
            Object out = pjp.proceed();
            if (out instanceof BraveSearchResult r) {
                BraveSearchResult.Status st = r.status();
                if (st == BraveSearchResult.Status.HTTP_429
                        || st == BraveSearchResult.Status.RATE_LIMIT_LOCAL
                        || st == BraveSearchResult.Status.COOLDOWN) {
                    Long retryAfterMs = (r.cooldownMs() > 0L) ? r.cooldownMs() : null;
                    backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_BRAVE, retryAfterMs,
                            "brave_meta_" + st, null);
                    TraceStore.put("web.brave.cooldown.startedNow", true);

                    // Align with canonical skip markers used by HybridWebSearchProvider KPIs.
                    // (Even though this call executed, it yielded a rate-limit-like status.)
                    markRateLimitedMeta("brave", retryAfterMs, st);
                } else if (st == BraveSearchResult.Status.OK
                        && r.snippets() != null && !r.snippets().isEmpty()) {
                    backoff.recordSuccess(RateLimitBackoffCoordinator.PROVIDER_BRAVE);
                }
            }
            return out;
        } catch (Throwable t) {
            if (isRateLimit429(t)) {
                Long retryAfterMs = extractRetryAfterMs(t);
                backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_BRAVE, retryAfterMs,
                        "brave_exception_429", extractRetryAfterHeader(t));
                TraceStore.put("web.brave.cooldown.startedNow", true);
                markRateLimited("brave", retryAfterMs, t);
                long cd = (retryAfterMs != null && retryAfterMs > 0L) ? retryAfterMs : 1000L;
                return BraveSearchResult.cooldown(cd, 0L);
            }
            throw t;
        }
    }

    private static void markSkipped(String provider, RateLimitBackoffCoordinator.Decision d) {
        try {
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".skipped", true);
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".remainingMs", d.remainingMs());
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".reason", safeStr(d.reason()));
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".justStarted", d.justStarted());

            // Canonical skip markers (ops KPI / downstream ladder compatibility)
            // NOTE: Do not overwrite an existing reason (e.g., breaker_open / hedge_skip).
            TraceStore.put("web." + provider + ".skipped", true);
            TraceStore.putIfAbsent("web." + provider + ".skipped.reason", "cooldown");
            TraceStore.putIfAbsent("web." + provider + ".skipped.stage", "aop_backoff");
            if (d.remainingMs() > 0L) {
                TraceStore.put("web." + provider + ".skipped.extraMs", d.remainingMs());
                TraceStore.putIfAbsent("web." + provider + ".cooldownMs", d.remainingMs());
            }
            TraceStore.put("web." + provider + ".skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web." + provider + ".skipped.count");

            String onceKey = "web.failsoft.rateLimitBackoff." + provider + ".skipped.logOnce";
            if (TraceStore.putIfAbsent(onceKey, Boolean.TRUE) == null) {
                if (d.justStarted()) {
                    log.info("[Nova] {} skipped by rate-limit backoff (remainingMs={}; justStarted={}; reason={}){}",
                            provider, d.remainingMs(), d.justStarted(), safeStr(d.reason()), LogCorrelation.suffix());
                } else if (log.isDebugEnabled()) {
                    log.debug("[Nova] {} skipped by rate-limit backoff (remainingMs={}; reason={}){}",
                            provider, d.remainingMs(), safeStr(d.reason()), LogCorrelation.suffix());
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static void markRateLimited(String provider, Long retryAfterMs, Throwable t) {
        try {
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".rateLimited", true);
            if (retryAfterMs != null) {
                TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".retryAfterMs", retryAfterMs);
            }

            // Canonical skip markers (ops KPI / downstream ladder compatibility)
            TraceStore.put("web." + provider + ".skipped", true);
            TraceStore.putIfAbsent("web." + provider + ".skipped.reason", "cooldown");
            TraceStore.putIfAbsent("web." + provider + ".skipped.stage", "aop_backoff");
            if (retryAfterMs != null && retryAfterMs > 0L) {
                TraceStore.put("web." + provider + ".skipped.extraMs", retryAfterMs);
                TraceStore.putIfAbsent("web." + provider + ".cooldownMs", retryAfterMs);
            }
            if (t != null) {
                TraceStore.put("web." + provider + ".skipped.err", t.getClass().getSimpleName());
            }
            TraceStore.put("web." + provider + ".skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web." + provider + ".skipped.count");

            String onceKey = "web.failsoft.rateLimitBackoff." + provider + ".logOnce";
            if (TraceStore.putIfAbsent(onceKey, true) == null) {
                log.warn("[Nova] {} rate-limit(429) intercepted; applying backoff (retryAfterMs={}; type={}){}",
                        provider, retryAfterMs, t.getClass().getSimpleName(), LogCorrelation.suffix());
            }
        } catch (Throwable ignore) {
        }
    }

    private static void markRateLimitedMeta(String provider, Long retryAfterMs, BraveSearchResult.Status st) {
        try {
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".rateLimited", true);
            if (retryAfterMs != null && retryAfterMs > 0L) {
                TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".retryAfterMs", retryAfterMs);
            }

            // Canonical skip markers (ops KPI / downstream ladder compatibility)
            TraceStore.put("web." + provider + ".skipped", true);
            String reason = (st == null) ? "rate_limit_meta" : ("brave_meta_" + st);
            TraceStore.put("web.failsoft.rateLimitBackoff." + provider + ".reason", reason);
            TraceStore.putIfAbsent("web." + provider + ".skipped.reason", "cooldown");
            TraceStore.putIfAbsent("web." + provider + ".skipped.stage", "aop_backoff");
            if (retryAfterMs != null && retryAfterMs > 0L) {
                TraceStore.put("web." + provider + ".skipped.extraMs", retryAfterMs);
                TraceStore.putIfAbsent("web." + provider + ".cooldownMs", retryAfterMs);
            }
            TraceStore.put("web." + provider + ".skipped.tsMs", System.currentTimeMillis());
            TraceStore.inc("web." + provider + ".skipped.count");
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private static boolean isTrueish(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.longValue() != 0L;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static boolean isRateLimit429(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof WebClientResponseException w) {
            return w.getRawStatusCode() == 429;
        }
        if (t instanceof HttpClientErrorException h) {
            return h.getStatusCode().value() == 429;
        }
        // nested
        return isRateLimit429(t.getCause());
    }

    private static String extractRetryAfterHeader(Throwable t) {
        try {
            if (t instanceof WebClientResponseException w) {
                return w.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            }
            if (t instanceof HttpClientErrorException h) {
                return h.getResponseHeaders() != null
                        ? h.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)
                        : null;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static Long extractRetryAfterMs(Throwable t) {
        String v = extractRetryAfterHeader(t);
        long ms = RateLimitBackoffCoordinator.parseRetryAfterMs(v, System.currentTimeMillis());
        return ms > 0L ? ms : null;
    }

    private static String safeStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
