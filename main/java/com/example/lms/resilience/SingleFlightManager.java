
package com.example.lms.resilience;

import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;



public class SingleFlightManager {
    private static final Logger LOG = LoggerFactory.getLogger(SingleFlightManager.class);

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    // Use a small pooled executor instead of creating a new executor per key.
    // Wrapped to propagate MDC/GuardContext and to avoid ThreadLocal leakage.
    private final ExecutorService exec = new ContextAwareExecutorService(
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "single-flight-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            }));

    public <T> T run(String key, Callable<T> task) throws Exception {
        CompletableFuture<Object> fut = inflight.computeIfAbsent(key, k -> {
            CompletableFuture<Object> f = new CompletableFuture<>();
            exec.submit(() -> {
                try {
                    f.complete(task.call());
                } catch (Exception e) {
                    traceSkipped("single_flight_task", e);
                    f.completeExceptionally(e);
                } finally {
                    inflight.remove(k);
                }
            });
            return f;
        });
        try {
            @SuppressWarnings("unchecked")
            T t = (T) fut.get();
            return t;
        } catch (ExecutionException ee) {
            throw new Exception(ee.getCause());
        }
    }

    private static void traceSkipped(String stage, Exception error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        LOG.debug("[AWX][single-flight] trace skipped stage={} errorType={}",
                safeStage, safeErrorType);
    }
}
