package com.example.lms.infra.upstash;

import com.example.lms.search.TraceStore;
import com.example.lms.service.web.WebResultCache;
import com.example.lms.trace.SafeRedactor;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.github.benmanes.caffeine.cache.Cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Two-tier web cache: local Caffeine first, optional Upstash Redis second.
 * This implementation is small and self-contained to prevent compile issues.
 */
@Component
public class UpstashBackedWebCache implements WebResultCache {

    private final Cache<String, String> local;
    private final UpstashRedisClient upstash;

    @Value("${upstash.cache.ttl-seconds:600}")
    private int defaultTtlSeconds;

    @Autowired
    public UpstashBackedWebCache(Cache<String,String> webLocalCache, UpstashRedisClient upstash) {
        this.local = webLocalCache;
        this.upstash = upstash;
    }

    @Override
    public Mono<Optional<String>> get(String key) {
        try {
            String v = local.getIfPresent(key);
            if (v != null) return Mono.just(Optional.of(v));
        } catch (RuntimeException ex) {
            traceSuppressed("getFallback", ex);
        }
        // remote
        return upstash.get(key)
                .map(Optional::ofNullable)
                .doOnNext(opt -> opt.ifPresent(val -> {
                    try { local.put(key, val); } catch (RuntimeException ex) { traceSuppressed("fillFallback", ex); }
                }))
                .onErrorReturn(Optional.empty());
    }

    @Override
    public Mono<Void> put(String key, String json, Duration ttl) {
        try { local.put(key, json); } catch (RuntimeException ex) { traceSuppressed("putFallback", ex); }
        Duration useTtl = (ttl == null || ttl.isZero() || ttl.isNegative())
                ? Duration.ofSeconds(defaultTtlSeconds)
                : ttl;
        return upstash.setEx(key, json, useTtl)
                .then();
    }

    private static void traceLocalFallback(String stage, RuntimeException ex) {
        String prefix = "web.cache.local." + stage;
        TraceStore.inc(prefix + ".count");
        TraceStore.put(prefix + ".errorType", errorType(ex));
    }

    private static String errorType(Throwable ex) {
        if (ex instanceof NumberFormatException) {
            return "invalid_number";
        }
        return ex == null ? "unknown" : SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
    }

    private static void traceSuppressed(String stage, RuntimeException ex) {
        traceLocalFallback(stage, ex);
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("web.cache.suppressed.stage", safeStage);
        TraceStore.put("web.cache.suppressed.errorType", errorType(ex));
        TraceStore.put("web.cache.suppressed." + safeStage, true);
    }
}
