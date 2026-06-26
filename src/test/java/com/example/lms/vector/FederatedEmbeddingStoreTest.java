package com.example.lms.vector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedEmbeddingStoreTest {

    @Test
    void federatedEmbeddingStoreDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/vector/FederatedEmbeddingStore.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "federated embedding store fail-soft paths need safe breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void failSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/vector/FederatedEmbeddingStore.java"),
                StandardCharsets.UTF_8);

        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("log.warn(\"Federated search fail-soft on store {}: {}\", ns.id()"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e2), 180)"));
        assertTrue(source.contains("log.warn(\"Federated search fail-soft on store {}. errorHash={} errorLength={}\","));
        assertTrue(source.contains("safeStoreId(ns.id()), SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("Federated addAll failed on store {}. errorHash={} errorLength={}"));
        assertTrue(source.contains("Federated addAll failed on store {} (fallback also failed). errorHash={} errorLength={}"));
        assertTrue(source.contains("Federated search fail-soft on store {}. errorHash={} errorLength={}"));
        assertTrue(source.contains("Federated addAll unsupported ids path store={}"));
        assertTrue(source.contains("[AWX2AF2][vector][store-error] store={} requestedK={} errorType={}"));
        assertTrue(source.contains("[AWX2AF2][vector][timeout] timeout breadcrumb suppressed store={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e2)), messageLength(e2)"));
    }

    @Test
    void searchReturnsHealthyStoreResultsWhenAnotherStoreTimesOut() {
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(
                        new FederatedEmbeddingStore.NamedStore("fast", new StaticStore("fast", 0)),
                        new FederatedEmbeddingStore.NamedStore("slow", new StaticStore("slow", 500))),
                new TopicRoutingSettings(Map.of("default", Map.of("fast", 1.0d, "slow", 1.0d)), 1),
                75,
                2);

        EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                .maxResults(2)
                .minScore(0.0)
                .build());

        assertEquals(1, result.matches().size());
        assertEquals("fast doc", result.matches().get(0).embedded().text());
    }

    @Test
    void unmatchedRoutingWeightsFallBackToStoreIdsInsteadOfDroppingAllK() {
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore("composite", new StaticStore("composite", 0))),
                new TopicRoutingSettings(Map.of("default", Map.of("pinecone", 1.0d)), 1),
                100,
                1);

        EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                .maxResults(1)
                .minScore(0.0)
                .build());

        assertEquals(1, result.matches().size());
        assertEquals("composite doc", result.matches().get(0).embedded().text());
    }

    @Test
    void timedOutStoreIsCancelledWithoutInterruptingWorker() throws Exception {
        TraceStore.clear();
        StaticStore slow = new StaticStore("slow", 300);
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(
                        new FederatedEmbeddingStore.NamedStore("fast", new StaticStore("fast", 0)),
                        new FederatedEmbeddingStore.NamedStore("slow", slow)),
                new TopicRoutingSettings(Map.of("default", Map.of("fast", 1.0d, "slow", 1.0d)), 1),
                50,
                2);

        try {
            EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                    .maxResults(2)
                    .minScore(0.0)
                    .build());

            assertEquals(1, result.matches().size());
            assertEquals("fast doc", result.matches().get(0).embedded().text());
            assertTrue(slow.started.await(1, TimeUnit.SECONDS));
            assertTrue(slow.finished.await(2, TimeUnit.SECONDS));
            assertFalse(slow.interrupted.get());
            assertEquals("no_interrupt", TraceStore.get("vector.federated.cancelMode"));
        } finally {
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    @Test
    void topicTraceDoesNotStoreRawFilterTopic() {
        TraceStore.clear();
        String rawSecret = "fixture-vector-topic-secret-1234567890";
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore("fast", new StaticStore("fast", 0))),
                new TopicRoutingSettings(Map.of("default", Map.of("fast", 1.0d)), 1),
                100,
                1);
        Filter rawTopicFilter = new Filter() {
            @Override
            public boolean test(Object object) {
                return true;
            }

            @Override
            public String toString() {
                return "MetadataFilter { key = 'topic', condition = EQUAL_TO, value = 'private vector topic api_key="
                        + rawSecret + "' }";
            }
        };

        try {
            EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                    .maxResults(1)
                    .minScore(0.0)
                    .filter(rawTopicFilter)
                    .build());

            assertEquals(1, result.matches().size());
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(rawSecret), trace);
            assertFalse(String.valueOf(TraceStore.get("vector.federated.topic")).contains("private vector topic"));
            assertTrue(String.valueOf(TraceStore.get("vector.federated.topic")).startsWith("hash:"));
        } finally {
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    @Test
    void traceDoesNotStoreRawConfiguredStoreIds() {
        TraceStore.clear();
        String rawStoreId = "tenant-vector-store token=private-vector-store";
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore(rawStoreId, new StaticStore("fast", 0))),
                new TopicRoutingSettings(Map.of("default", Map.of(rawStoreId, 1.0d)), 1),
                100,
                1);

        try {
            EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                    .maxResults(1)
                    .minScore(0.0)
                    .build());

            assertEquals(1, result.matches().size());
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(rawStoreId), trace);
            assertFalse(trace.contains("private-vector-store"), trace);
            assertTrue(trace.contains("hash:"), trace);
        } finally {
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    private static final class StaticStore implements EmbeddingStore<TextSegment> {
        private final String id;
        private final long delayMs;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean(false);

        private StaticStore(String id, long delayMs) {
            this.id = id;
            this.delayMs = delayMs;
        }

        @Override
        public String add(Embedding embedding) {
            return id;
        }

        @Override
        public void add(String id, Embedding embedding) {
        }

        @Override
        public String add(Embedding embedding, TextSegment embedded) {
            return id;
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            return Collections.emptyList();
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
            return Collections.emptyList();
        }

        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            started.countDown();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    return new EmbeddingSearchResult<>(List.of());
                } finally {
                    finished.countDown();
                }
            } else {
                finished.countDown();
            }
            TextSegment segment = TextSegment.from(id + " doc", Metadata.from(Map.of("source", id)));
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
            matches.add(new EmbeddingMatch<>(0.9, id + "-1", request.queryEmbedding(), segment));
            return new EmbeddingSearchResult<>(matches);
        }
    }
}
