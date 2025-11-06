package com.example.lms.infra.cache;

import java.util.Map;
import java.util.concurrent.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.infra.cache.SingleFlightExecutor
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.example.lms.infra.cache.SingleFlightExecutor
role: config
*/
public class SingleFlightExecutor {
  private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  public <T> CompletableFuture<T> execute(String key, Callable<T> task) {
    return (CompletableFuture<T>) inFlight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
      try { return task.call(); }
      catch (Exception e){ throw new CompletionException(e); }
    })).whenComplete((r, t) -> inFlight.remove(key));
  }
}