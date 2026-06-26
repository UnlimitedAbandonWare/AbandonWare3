package com.example.lms.vector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Primary;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;


import dev.langchain4j.store.embedding.filter.Filter;
/**
 * FederatedEmbeddingStore composes multiple {@link EmbeddingStore} instances
 * into a single store.  During search operations it consults the
 * {@link TopicRoutingSettings} to determine how many results to request
 * from each store based on the desired topK and the inferred topic.  It
 * then merges, normalises and deduplicates the results to produce a
 * unified list.  Write operations fan-out to all stores but failures in
 * any individual store are suppressed (fail-soft).
 */
@Component
@Primary
public class FederatedEmbeddingStore implements EmbeddingStore<TextSegment> {
    private static final Logger log = LoggerFactory.getLogger(FederatedEmbeddingStore.class);
    /** Simple wrapper of a named EmbeddingStore. */
    public record NamedStore(String id, EmbeddingStore<TextSegment> store) {}

    private final List<NamedStore> stores;
    private final TopicRoutingSettings routing;
    private final long searchTimeoutMs;
    private final int maxParallelism;
    private final ExecutorService pool;

    // Use a context-aware pool so MDC/GuardContext survives on pooled workers.
    // (Even if the vector store itself doesn't rely on GuardContext today, downstream
    // stores or tracing often do.)
    private static ExecutorService newPool(int maxParallelism) {
        int threads = Math.max(1, Math.min(64, maxParallelism));
        AtomicInteger seq = new AtomicInteger();
        return new ContextAwareExecutorService(
                Executors.newFixedThreadPool(threads, r -> {
                    Thread t = new Thread(r, "vec-federated-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }));
    }


    // MERGE_HOOK:PROJ_AGENT::FED_STORE_LIST_BEAN_INJECT_V1
    @Autowired
    public FederatedEmbeddingStore(
            @Qualifier("federatedEmbeddingStores") ObjectProvider<List<NamedStore>> storesProvider,
            TopicRoutingSettings routing,
            @Value("${vector.federated.search-timeout-ms:5000}") long searchTimeoutMs,
            @Value("${vector.federated.max-parallelism:8}") int maxParallelism
    ) {
        List<NamedStore> resolved = (storesProvider == null)
                ? Collections.emptyList()
                : storesProvider.getIfAvailable(Collections::emptyList);
        this.stores = (resolved == null) ? Collections.emptyList() : List.copyOf(resolved);
        this.routing = routing == null ? new TopicRoutingSettings(Collections.emptyMap(), 1) : routing;
        this.searchTimeoutMs = Math.max(50L, searchTimeoutMs);
        this.maxParallelism = Math.max(1, maxParallelism);
        this.pool = newPool(this.maxParallelism);
    }

    FederatedEmbeddingStore(List<NamedStore> stores,
                            TopicRoutingSettings routing,
                            long searchTimeoutMs,
                            int maxParallelism) {
        this.stores = stores == null ? Collections.emptyList() : List.copyOf(stores);
        this.routing = routing == null ? new TopicRoutingSettings(Collections.emptyMap(), 1) : routing;
        this.searchTimeoutMs = Math.max(50L, searchTimeoutMs);
        this.maxParallelism = Math.max(1, maxParallelism);
        this.pool = newPool(this.maxParallelism);
    }
    @PostConstruct
    void logInitialization() {
        if (stores == null || stores.isEmpty()) {
            log.warn("FederatedEmbeddingStore initialized with 0 underlying store(s). " +
                    "Vector search will always return empty results. " +
                    "Check FederatedVectorStorePatchConfig / EmbeddingStore beans.");
        } else {
            List<String> ids = safeStoreIds(stores.stream().map(NamedStore::id).toList());
            log.info("FederatedEmbeddingStore initialized with {} store(s): {} timeoutMs={} maxParallelism={}",
                    ids.size(), ids, searchTimeoutMs, maxParallelism);
        }
    }

    @PreDestroy
    void shutdownPool() {
        try {
            pool.shutdownNow();
        } catch (Exception ignore) {
            logSuppressed("pool.shutdown", ignore);
        }
    }

    /**
     * Return an immutable list of the identifiers for the underlying stores
     * composing this FederatedEmbeddingStore.  When no stores are present
     * an empty list is returned.  This method can be used for diagnostics
     * and logging of the vector routing configuration.
     *
     * @return list of store identifiers, never {@code null}
     */
    public List<String> describeStoreIds() {
        if (stores == null || stores.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return stores.stream().map(NamedStore::id).collect(java.util.stream.Collectors.toUnmodifiableList());
    }

    @Override
    public String add(dev.langchain4j.data.embedding.Embedding embedding) {
        String id = UUID.randomUUID().toString();
        addAll(List.of(embedding), List.of(TextSegment.from("", Metadata.from(Collections.emptyMap()))));
        return id;
    }

    @Override
    public void add(String id, dev.langchain4j.data.embedding.Embedding embedding) {
        add(id, embedding, null);
    }

    @Override
    public String add(dev.langchain4j.data.embedding.Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        addAll(List.of(id), List.of(embedding), List.of(embedded));
        return id;
    }

    @Override
    public List<String> addAll(List<dev.langchain4j.data.embedding.Embedding> embeddings) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        addAll(ids, embeddings, Collections.nCopies(embeddings.size(), null));
        return ids;
    }

    @Override
    public List<String> addAll(List<dev.langchain4j.data.embedding.Embedding> embeddings, List<TextSegment> segments) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        addAll(ids, embeddings, segments);
        return ids;
    }

    private void add(String id, dev.langchain4j.data.embedding.Embedding embedding, TextSegment segment) {
        addAll(List.of(id), List.of(embedding), List.of(segment));
    }
    @Override
    public void addAll(List<String> ids, List<dev.langchain4j.data.embedding.Embedding> embeddings, List<TextSegment> segments) {
        if (stores == null || stores.isEmpty()) {
            throw new IllegalStateException("FederatedEmbeddingStore has no upstream stores (stores=empty)");
        }

        int ok = 0;
        java.util.List<String> failed = new java.util.ArrayList<>();

        for (NamedStore ns : stores) {
            try {
                ns.store().addAll(ids, embeddings, segments);
                ok++;
            } catch (dev.langchain4j.exception.UnsupportedFeatureException uf) {
                log.debug("Federated addAll unsupported ids path store={}", safeStoreId(ns.id()));
                // Fallback: stores that don't support addAll(ids,...)
                try {
                    if (segments != null) {
                        ns.store().addAll(embeddings, segments);
                    } else {
                        ns.store().addAll(embeddings);
                    }
                    ok++;
                    log.warn("Federated addAll degraded on store {}: addAll(ids,..) unsupported -> fallback used",
                            safeStoreId(ns.id()));
                } catch (Exception e2) {
                    failed.add(safeStoreId(ns.id()) + ":" + e2.getClass().getSimpleName());
                    log.warn("Federated addAll failed on store {} (fallback also failed). errorHash={} errorLength={}",
                            safeStoreId(ns.id()), SafeRedactor.hashValue(messageOf(e2)), messageLength(e2));
                }
            } catch (Exception e) {
                failed.add(safeStoreId(ns.id()) + ":" + e.getClass().getSimpleName());
                log.warn("Federated addAll failed on store {}. errorHash={} errorLength={}",
                        safeStoreId(ns.id()), SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            }
        }

        // Prevent silent drops: if nobody accepted the write, propagate to the caller.
        if (ok == 0) {
            throw new IllegalStateException("Federated addAll failed on all stores: " + String.join(",", failed));
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest req) {
        Embedding q = (req == null ? null : req.queryEmbedding());
        if (q == null || q.vector() == null || q.vector().length == 0) {
            log.warn("FederatedEmbeddingStore.search called with empty embedding; skipping all stores");
            return new EmbeddingSearchResult<>(Collections.emptyList());
        }
        if (isAllZeroVector(q.vector())) {
            log.warn("FederatedEmbeddingStore.search called with all-zero embedding; skipping all stores");
            return new EmbeddingSearchResult<>(Collections.emptyList());
        }
        String topic = extractTopicFromFilter(req.filter()).orElse("default");
        String topicTrace = safeTopic(topic);

        Map<String, Double> configuredWeights = routing.weightsFor(topic);
        Map<String, Double> weights = weightsMatchingStores(configuredWeights);
        Set<String> unmatchedWeightKeys = unmatchedWeightKeys(configuredWeights);
        if (!unmatchedWeightKeys.isEmpty()) {
            log.warn("[AWX2AF2][vector][route] unmatched vector.routing weight keys topic={} keys={} stores={}",
                    topicTrace, safeStoreIds(unmatchedWeightKeys), safeStoreIds(stores.stream().map(NamedStore::id).toList()));
            trace("vector.federated.unmatchedWeightKeys", safeStoreIds(unmatchedWeightKeys).toString());
        }
        int k = Math.max(1, req.maxResults());
        Map<String, Integer> split = allocateK(weights, k, routing.minPerStore());
        List<SearchTask> futures = new ArrayList<>();
        for (NamedStore ns : stores) {
            int ki = split.getOrDefault(ns.id(), 0);
            if (ki <= 0) continue;
            EmbeddingSearchRequest subReq = EmbeddingSearchRequest.builder()
                    .queryEmbedding(req.queryEmbedding())
                    .maxResults(ki)
                    .minScore(req.minScore())
                    .filter(req.filter())
                    .build();
            long started = System.nanoTime();
            Future<EmbeddingSearchResult<TextSegment>> future = pool.submit(() -> {
                try {
                    return ns.store().search(subReq);
                } catch (Exception e) {
                    log.warn("Federated search fail-soft on store {}. errorHash={} errorLength={}",
                            safeStoreId(ns.id()), SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                    return new EmbeddingSearchResult<>(Collections.emptyList());
                }
            });
            futures.add(new SearchTask(ns.id(), ki, started, future));
        }
        List<EmbeddingMatch<TextSegment>> merged = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (SearchTask task : futures) {
            try {
                EmbeddingSearchResult<TextSegment> r = task.future().get(searchTimeoutMs, TimeUnit.MILLISECONDS);
                long tookMs = elapsedMs(task.startedNanos());
                int returned = (r == null || r.matches() == null) ? 0 : r.matches().size();
                diagnostics.add(safeStoreId(task.storeId()) + ":ok:k=" + task.requestedK() + ":returned=" + returned + ":tookMs=" + tookMs);
                if (r != null && r.matches() != null) {
                    merged.addAll(r.matches());
                }
            } catch (TimeoutException te) {
                task.future().cancel(false);
                recordTimeoutCancellation(task.storeId(), task.requestedK());
                diagnostics.add(safeStoreId(task.storeId()) + ":timeout:k=" + task.requestedK() + ":timeoutMs=" + searchTimeoutMs);
                log.warn("[AWX2AF2][vector][timeout] store={} requestedK={} timeoutMs={}",
                        safeStoreId(task.storeId()), task.requestedK(), searchTimeoutMs);
            } catch (Exception ignored) {
                diagnostics.add(safeStoreId(task.storeId()) + ":error:" + ignored.getClass().getSimpleName());
                log.warn("[AWX2AF2][vector][store-error] store={} requestedK={} errorType={}",
                        safeStoreId(task.storeId()), task.requestedK(),
                        SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown"));
            }
        }
        trace("vector.federated.topic", topicTrace);
        trace("vector.federated.split", safeSplit(split).toString());
        trace("vector.federated.diagnostics", diagnostics.toString());
        trace("vector.federated.timeoutMs", searchTimeoutMs);
        if (merged.isEmpty()) {
            // MERGE_HOOK:PROJ_AGENT::FEDERATED_ROUTE_LABEL
            // Normalised log line used by GPU/RAG diagnostics; keep shape stable.
            log.info("ROUTE_LABEL topic={} weights={} split={} k={} stores={} diagnostics={}",
                    topicTrace, safeSplit(weights), safeSplit(split), k,
                    safeStoreIds(stores.stream().map(NamedStore::id).toList()), diagnostics);
            log.info("[AWX2AF2][vector][zero-results] topic={} returnedCount=0 stores={} split={} diagnostics={}",
                    topicTrace,
                    safeStoreIds(stores.stream().map(NamedStore::id).toList()), safeSplit(split), diagnostics);
            return new EmbeddingSearchResult<>(Collections.emptyList());
        }

        double min = merged.stream().mapToDouble(EmbeddingMatch::score).min().orElse(0.0);
        double max = merged.stream().mapToDouble(EmbeddingMatch::score).max().orElse(1.0);
        Map<String, EmbeddingMatch<TextSegment>> dedup = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> m : merged) {
            double norm = (max > min) ? (m.score() - min) / (max - min) : m.score();
            String key = keyOf(m.embedded());
            EmbeddingMatch<TextSegment> prev = dedup.get(key);
            if (prev == null || norm > prev.score()) {
                dedup.put(key, new EmbeddingMatch<>(norm, m.embeddingId(), m.embedding(), m.embedded()));
            }
        }
        List<EmbeddingMatch<TextSegment>> sorted = dedup.values().stream()
                .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed())
                .limit(k)
                .collect(Collectors.toList());
        return new EmbeddingSearchResult<>(sorted);
    }

    private void recordTimeoutCancellation(String storeId, int requestedK) {
        try {
            TraceStore.put("vector.federated.cancelMode", "no_interrupt");
            TraceStore.put("vector.federated.timeout", true);
            TraceStore.inc("vector.federated.timeout.count");
            TraceStore.append("vector.federated.timeout.events", Map.of(
                    "store", safeStoreId(storeId),
                    "requestedK", Math.max(0, requestedK),
                    "timeoutMs", searchTimeoutMs,
                    "cancelMode", "no_interrupt"));
        } catch (Throwable ignore) {
            log.debug("[AWX2AF2][vector][timeout] timeout breadcrumb suppressed store={}",
                    safeStoreId(storeId));
        }
    }

    private record SearchTask(String storeId,
                              int requestedK,
                              long startedNanos,
                              Future<EmbeddingSearchResult<TextSegment>> future) {
    }

    private Map<String, Double> weightsMatchingStores(Map<String, Double> configuredWeights) {
        if (configuredWeights == null || configuredWeights.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> ids = stores.stream().map(NamedStore::id).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : configuredWeights.entrySet()) {
            if (e.getKey() != null && ids.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue() == null ? 0.0d : e.getValue());
            }
        }
        return out;
    }

    private Set<String> unmatchedWeightKeys(Map<String, Double> configuredWeights) {
        if (configuredWeights == null || configuredWeights.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> ids = stores.stream().map(NamedStore::id).collect(Collectors.toSet());
        Set<String> out = new LinkedHashSet<>();
        for (String key : configuredWeights.keySet()) {
            if (key != null && !ids.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private Map<String, Integer> allocateK(Map<String, Double> weights, int k, int minPerStore) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (weights == null || weights.isEmpty()) {
            int each = Math.max(minPerStore, k / Math.max(1, stores.size()));
            for (NamedStore ns : stores) {
                out.put(ns.id(), each);
            }
        } else {
            double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            int assigned = 0;
            for (NamedStore ns : stores) {
                double w = weights.getOrDefault(ns.id(), 0.0);
                int v = (int) Math.round(k * (total > 0 ? (w / total) : 0));
                out.put(ns.id(), v);
                assigned += v;
            }
            while (assigned < k) {
                String best = bestStoreByWeight(weights);
                out.put(best, out.getOrDefault(best, 0) + 1);
                assigned++;
            }
            while (assigned > k) {
                String worst = worstAllocatedStore(out, weights, 0);
                int cur = out.getOrDefault(worst, 0);
                if (cur > 0) {
                    out.put(worst, cur - 1);
                    assigned--;
                } else {
                    break;
                }
            }
        }
        if (minPerStore > 0) {
            int sum = out.values().stream().mapToInt(Integer::intValue).sum();
            for (NamedStore ns : stores) {
                out.put(ns.id(), Math.max(minPerStore, out.getOrDefault(ns.id(), 0)));
            }
            sum = out.values().stream().mapToInt(Integer::intValue).sum();
            while (sum > k) {
                String target = worstAllocatedStore(out, weights, minPerStore);
                int cur = out.getOrDefault(target, 0);
                if (cur > minPerStore) {
                    out.put(target, cur - 1);
                    sum--;
                } else {
                    Optional<String> alt = out.entrySet().stream().filter(e -> e.getValue() > minPerStore).map(Map.Entry::getKey).findFirst();
                    if (alt.isPresent()) {
                        out.put(alt.get(), out.get(alt.get()) - 1);
                        sum--;
                    } else {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private String bestStoreByWeight(Map<String, Double> weights) {
        return stores.stream()
                .map(NamedStore::id)
                .max(Comparator.comparingDouble(id -> weights.getOrDefault(id, 0.0d)))
                .orElse(stores.isEmpty() ? "default" : stores.get(0).id());
    }

    private String worstAllocatedStore(Map<String, Integer> allocated, Map<String, Double> weights, int floor) {
        return stores.stream()
                .map(NamedStore::id)
                .filter(id -> allocated.getOrDefault(id, 0) > floor)
                .min(Comparator.comparingDouble(id -> weights.getOrDefault(id, 0.0d)))
                .orElse(stores.isEmpty() ? "default" : stores.get(0).id());
    }

    private static String keyOf(TextSegment seg) {
        if (seg == null) return "";
        String text = seg.text();
        String source = "";
        try {
            if (seg.metadata() != null) {
                source = Optional.ofNullable(seg.metadata().getString("source")).orElse("");
            }
        } catch (Exception ignored) {
            logSuppressed("dedup.metadata", ignored);
        }
        return Integer.toHexString(Objects.hash(text, source));
    }
    /**
     * Safely extracts the value of a key (e.g., "topic") from a simple ComparisonFilter.
     * This helper method avoids ClassCastExceptions and handles nulls gracefully.
     * NOTE: It currently supports simple "key = value" filters and not complex logical operators.
     * @param filter The filter to inspect.
     * @return An Optional containing the value if found, otherwise empty.
     */
    /**
     * [HIGH-RISK] Extracts a metadata value from a LangChain4j 1.0.1 Filter object by parsing its toString() representation.
     * This is a brittle workaround due to the lack of introspection APIs for Filter objects in this specific library version.
     * It assumes a filter created like: {@code metadataKey("topic").isEqualTo("some-value")}.
     *
     * @param filter The Filter object to inspect.
     * @return An Optional containing the value for the 'topic' key if found.
     */
    private Optional<String> extractTopicFromFilter(Filter filter) {
        if (filter == null) {
            return Optional.empty();
        }

        // This regex pattern is designed to match the typical toString() output of a simple metadata filter in LangChain4j v1.0.1.
        // Example: "MetadataFilter { key = 'topic', condition = EQUAL_TO, value = 'some-value' }"
        Pattern pattern = Pattern.compile("key\\s*=\\s*'topic'\\s*,\\s*.*?value\\s*=\\s*'(.*?)'");
        Matcher matcher = pattern.matcher(filter.toString());

        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }

        return Optional.empty();
    }

    private boolean isAllZeroVector(float[] v) {
        if (v == null || v.length == 0) {
            return true;
        }
        for (float x : v) {
            if (x != 0.0f) {
                return false;
            }
        }
        return true;
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static void logSuppressed(String stage, Throwable ignored) {
        if (log.isDebugEnabled()) {
            log.debug("[vector-federated] suppressed stage={}",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        }
    }

    private static String safeTopic(String topic) {
        return SafeRedactor.traceLabelOrFallback(topic, "default");
    }

    private static String safeStoreId(String storeId) {
        return SafeRedactor.traceLabelOrFallback(storeId, "store");
    }

    private static List<String> safeStoreIds(Collection<String> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of();
        }
        return storeIds.stream().map(FederatedEmbeddingStore::safeStoreId).toList();
    }

    private static <N extends Number> Map<String, N> safeSplit(Map<String, N> split) {
        if (split == null || split.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, N> out = new LinkedHashMap<>();
        for (Map.Entry<String, N> entry : split.entrySet()) {
            if (entry == null) {
                continue;
            }
            out.put(safeStoreId(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static void trace(String key, Object value) {
        try {
            TraceStore.put(key, value);
        } catch (Exception ignore) {
            logSuppressed("trace.put", ignore);
        }
    }
}
