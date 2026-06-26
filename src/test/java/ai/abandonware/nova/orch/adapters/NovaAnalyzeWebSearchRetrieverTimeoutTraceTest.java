package ai.abandonware.nova.orch.adapters;

import com.example.lms.search.TraceStore;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.policy.SearchPolicyDecision;
import com.example.lms.search.policy.SearchPolicyMode;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.routing.plan.RoutingPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaAnalyzeWebSearchRetrieverTimeoutTraceTest {
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        TraceStore.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void hardTimeoutCancellationEmitsRedactedAwaitEventWithoutInterruptingWorker() throws Exception {
        String rawQuery = "raw analyze timeout query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        BlockingProvider provider = new BlockingProvider("planned slow query");
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                provider,
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned slow query")),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());
        retriever.setTimeoutMs(1);

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(
                rawQuery,
                Map.of("searchPolicyMode", "OFF")));
        assertTrue(provider.entered.await(1, TimeUnit.SECONDS));
        provider.release.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));

        assertTrue(out.isEmpty());
        assertFalse(provider.interrupted.get());
        assertEquals(2, provider.calls.get());
        assertEquals(1L, TraceStore.getLong("web.await.cancelSuppressed"));
        assertEquals("timeout_hard", TraceStore.get("web.await.cancelSuppressed.reason"));
        assertEquals(1L, TraceStore.getLong("web.await.analyze.cancelAttempted"));
        assertEquals(1L, TraceStore.getLong("web.await.analyze.cancelSucceeded"));
        assertEquals(250L, TraceStore.getLong("web.await.analyze.timeoutMs"));
        assertTrue(String.valueOf(TraceStore.get("web.await.analyze.queryHash12")).matches("[0-9a-f]{12}"));

        Object eventsObj = TraceStore.get("web.await.events");
        assertTrue(eventsObj instanceof List<?>);
        Map<String, Object> event = (Map<String, Object>) ((List<?>) eventsObj).get(0);
        assertEquals("NovaAnalyze", event.get("engine"));
        assertEquals("timeout_hard", event.get("cause"));
        assertEquals(Boolean.TRUE, event.get("timeout"));
        assertEquals(Boolean.TRUE, event.get("hardTimeout"));
        assertEquals(Boolean.TRUE, event.get("cancelSuppressed"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void policyDecisionFailureLeavesRedactedTraceAndContinuesSearch() {
        String rawQuery = "policy failure query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new FixedProvider(List.of("policy fallback result")),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned policy query")),
                new ThrowingSearchPolicyEngine(),
                executor,
                new ObjectMapper());

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

        assertEquals(1, out.size());
        assertEquals("exception", TraceStore.get("web.analyze.searchPolicy.failureReason"));
        assertEquals("IllegalStateException", TraceStore.get("web.analyze.searchPolicy.errorType"));
        assertTrue(String.valueOf(TraceStore.get("web.analyze.searchPolicy.queryHash12")).matches("[0-9a-f]{12}"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void policyApplyFailureLeavesRedactedTraceAndUsesBasePlan() {
        String rawQuery = "policy apply query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new FixedProvider(List.of("base plan result")),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned base query")),
                new ThrowingPolicyApplyEngine(),
                executor,
                new ObjectMapper());

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

        assertEquals(1, out.size());
        assertEquals("apply", TraceStore.get("web.analyze.searchPolicy.stage"));
        assertEquals("exception", TraceStore.get("web.analyze.searchPolicy.failureReason"));
        assertEquals("IllegalArgumentException", TraceStore.get("web.analyze.searchPolicy.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void plannedSearchFailureLeavesRedactedTraceAndUsesOriginalFallback() {
        String rawQuery = "planned fallback query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new ThrowingPlannedProvider("planned failing query", rawQuery),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned failing query")),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

        assertEquals(1, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.plannedSearch.failed"));
        assertEquals("exception", TraceStore.get("web.analyze.plannedSearch.failureReason"));
        assertEquals("IllegalStateException", TraceStore.get("web.analyze.plannedSearch.errorType"));
        assertTrue(String.valueOf(TraceStore.get("web.analyze.plannedSearch.queryHash12")).matches("[0-9a-f]{12}"));
        assertEquals("planned failing query".length(), TraceStore.get("web.analyze.plannedSearch.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void partialFutureFailureLeavesRedactedTraceAndUsesOriginalFallback() {
        String rawQuery = "partial future query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new ThrowingErrorProvider("planned error query", rawQuery),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned error query")),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

        assertEquals(1, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.partialSearch.failed"));
        assertEquals("exception", TraceStore.get("web.analyze.partialSearch.failureReason"));
        assertEquals("AssertionError", TraceStore.get("web.analyze.partialSearch.errorType"));
        assertTrue(String.valueOf(TraceStore.get("web.analyze.partialSearch.queryHash12")).matches("[0-9a-f]{12}"));
        assertEquals(rawQuery.length(), TraceStore.get("web.analyze.partialSearch.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void fallbackSearchFailureLeavesRedactedTraceAndReturnsEmpty() {
        String rawQuery = "fallback analyze query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new ThrowingOriginalFallbackProvider(rawQuery),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned empty query")),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

        assertTrue(out.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.fallbackSearch.failed"));
        assertEquals("exception", TraceStore.get("web.analyze.fallbackSearch.failureReason"));
        assertEquals("IllegalStateException", TraceStore.get("web.analyze.fallbackSearch.errorType"));
        assertTrue(String.valueOf(TraceStore.get("web.analyze.fallbackSearch.queryHash12")).matches("[0-9a-f]{12}"));
        assertEquals(rawQuery.length(), TraceStore.get("web.analyze.fallbackSearch.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void invalidMetaIntegerFallbackUsesStableReasonCodeWithoutRawValue() {
        Integer out = ReflectionTestUtils.invokeMethod(
                NovaAnalyzeWebSearchRetriever.class,
                "metaInt",
                Map.of("webTopK", "private topK value"),
                "webTopK",
                7);

        assertEquals(7, out);
        assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.metaInt.parseFallback"));
        assertEquals("webTopK", TraceStore.get("web.analyze.metaInt.parseFallback.key"));
        assertEquals("invalid_number", TraceStore.get("web.analyze.metaInt.parseFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private topK value"));
    }

    @Test
    void nonFiniteMetaIntegerFallbackUsesStableReasonCode() {
        Integer out = ReflectionTestUtils.invokeMethod(
                NovaAnalyzeWebSearchRetriever.class,
                "metaInt",
                Map.of("webTopK", Double.POSITIVE_INFINITY),
                "webTopK",
                7);

        assertEquals(7, out);
        assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.metaInt.parseFallback"));
        assertEquals("webTopK", TraceStore.get("web.analyze.metaInt.parseFallback.key"));
        assertEquals("invalid_number", TraceStore.get("web.analyze.metaInt.parseFallback.errorType"));
    }

    @Test
    void interruptedAndCancelledTraceUsesStableReasonCodeWithoutRawQuery() {
        String rawQuery = "private analyze interrupted query ownerToken=fake-token";

        ReflectionTestUtils.invokeMethod(
                NovaAnalyzeWebSearchRetriever.class,
                "traceInterruptedPoll",
                rawQuery,
                new InterruptedException("ownerToken=fake-token"));
        assertEquals("cancelled", TraceStore.get("web.await.analyze.interrupted.reason"));
        assertEquals("cancelled", TraceStore.get("web.await.analyze.interrupted.errorType"));

        ReflectionTestUtils.invokeMethod(
                NovaAnalyzeWebSearchRetriever.class,
                "traceCancelFailure",
                rawQuery,
                new CancellationException("ownerToken=fake-token"));
        assertEquals("cancelled", TraceStore.get("web.await.analyze.cancelFailure.reason"));
        assertEquals("cancelled", TraceStore.get("web.await.analyze.cancelFailure.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    private static final class FixedRoutingPlanService extends RoutingPlanService {
        private final List<String> queries;

        private FixedRoutingPlanService(List<String> queries) {
            super(null, null, null);
            this.queries = queries;
        }

        @Override
        public List<String> plan(String userPrompt, String assistantDraft, int maxQueries) {
            return queries;
        }
    }

    private static final class BlockingProvider implements WebSearchProvider {
        private final String blockedQuery;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean(false);
        private final AtomicInteger calls = new AtomicInteger();

        private BlockingProvider(String blockedQuery) {
            this.blockedQuery = blockedQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            calls.incrementAndGet();
            if (!blockedQuery.equals(query)) {
                return List.of();
            }
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                TraceStore.put("test.novaAnalyzeWebSearch.blockingProvider.interrupted", true);
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "blocking";
        }
    }

    private static final class FixedProvider implements WebSearchProvider {
        private final List<String> results;

        private FixedProvider(List<String> results) {
            this.results = results;
        }

        @Override
        public List<String> search(String query, int topK) {
            return results;
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "fixed";
        }
    }

    private static final class ThrowingOriginalFallbackProvider implements WebSearchProvider {
        private final String originalQuery;

        private ThrowingOriginalFallbackProvider(String originalQuery) {
            this.originalQuery = originalQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            if (originalQuery.equals(query)) {
                throw new IllegalStateException("raw fallback api_key=" + com.example.lms.test.SecretFixtures.openAiKey());
            }
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "throwing-original";
        }
    }

    private static final class ThrowingPlannedProvider implements WebSearchProvider {
        private final String plannedQuery;
        private final String originalQuery;

        private ThrowingPlannedProvider(String plannedQuery, String originalQuery) {
            this.plannedQuery = plannedQuery;
            this.originalQuery = originalQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            if (plannedQuery.equals(query)) {
                throw new IllegalStateException("raw planned api_key=" + com.example.lms.test.SecretFixtures.openAiKey());
            }
            if (originalQuery.equals(query)) {
                return List.of("original fallback result");
            }
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "throwing-planned";
        }
    }

    private static final class ThrowingErrorProvider implements WebSearchProvider {
        private final String plannedQuery;
        private final String originalQuery;

        private ThrowingErrorProvider(String plannedQuery, String originalQuery) {
            this.plannedQuery = plannedQuery;
            this.originalQuery = originalQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            if (plannedQuery.equals(query)) {
                throw new AssertionError("raw partial api_key=" + com.example.lms.test.SecretFixtures.openAiKey());
            }
            if (originalQuery.equals(query)) {
                return List.of("original fallback after future failure");
            }
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "throwing-error";
        }
    }

    private static final class ThrowingSearchPolicyEngine extends SearchPolicyEngine {
        @Override
        public SearchPolicyDecision decide(String query, Map<String, Object> metaHints) {
            throw new IllegalStateException("raw policy failure api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + "");
        }
    }

    private static final class ThrowingPolicyApplyEngine extends SearchPolicyEngine {
        @Override
        public SearchPolicyDecision decide(String query, Map<String, Object> metaHints) {
            return new SearchPolicyDecision(
                    SearchPolicyMode.BALANCED,
                    true,
                    true,
                    4,
                    2,
                    1,
                    2,
                    1,
                    1.0d,
                    1.0d,
                    "test-policy");
        }

        @Override
        public List<String> apply(List<String> basePlanned, String originalQuery, SearchPolicyDecision d) {
            throw new IllegalArgumentException("raw policy apply api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + "");
        }
    }
}
