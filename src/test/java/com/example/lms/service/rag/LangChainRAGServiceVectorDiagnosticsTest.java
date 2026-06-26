package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangChainRAGServiceVectorDiagnosticsTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void vectorRetrieverRecordsNoMatchesWithoutRawQuery() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embed("vector starvation")).thenReturn(Response.from(Embedding.from(new float[] {1.0f})));
        when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of()));
        LangChainRAGService service = new LangChainRAGService(model, store);

        List<Content> out = service.asContentRetriever("test-index").retrieve(new Query("vector starvation"));

        assertTrue(out.isEmpty());
        assertEquals(5, TraceStore.get("vector.retrieval.requestedTopK"));
        assertEquals(5, TraceStore.get("vector.retrieval.poolK"));
        assertEquals(0, TraceStore.get("vector.retrieval.rawMatchCount"));
        assertEquals(0, TraceStore.get("vector.retrieval.keptCount"));
        assertEquals("no_matches", TraceStore.get("vector.retrieval.emptyReason"));
        assertEquals("none", TraceStore.get("vector.retrieval.failureClass"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("vector starvation"));
    }

    @Test
    void vectorRetrieverRecordsFailureClassWithoutExceptionBody() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embed("sensitive vector query")).thenReturn(Response.from(Embedding.from(new float[] {1.0f})));
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenThrow(new IllegalStateException("raw sensitive body"));
        LangChainRAGService service = new LangChainRAGService(model, store);

        List<Content> out = service.asContentRetriever("test-index").retrieve(new Query("sensitive vector query"));

        assertTrue(out.isEmpty());
        assertEquals("exception", TraceStore.get("vector.retrieval.emptyReason"));
        assertEquals("IllegalStateException", TraceStore.get("vector.retrieval.failureClass"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("sensitive vector query"));
        assertFalse(trace.contains("raw sensitive body"));
    }

    @Test
    void activeSidResolverFailureLeavesTraceStoreBreadcrumb() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        com.example.lms.service.vector.VectorSidService sidService =
                mock(com.example.lms.service.vector.VectorSidService.class);
        when(sidService.resolveActiveSid(anyString()))
                .thenThrow(new IllegalStateException("ownerToken=raw-sid-secret"));
        LangChainRAGService service = new LangChainRAGService(model, store);
        java.lang.reflect.Field field = LangChainRAGService.class.getDeclaredField("vectorSidService");
        field.setAccessible(true);
        field.set(service, sidService);
        Method method = LangChainRAGService.class.getDeclaredMethod("activeGlobalSid");
        method.setAccessible(true);

        assertEquals(LangChainRAGService.GLOBAL_SID, method.invoke(service));
        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.activeGlobalSid.suppressed"));
        assertEquals("IllegalStateException", TraceStore.get("vector.retrieval.activeGlobalSid.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=raw-sid-secret"));
    }

    @Test
    void serviceLogsDoNotUseRawThrowableMessagesOrRawSid() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/LangChainRAGService.java"),
                StandardCharsets.UTF_8);
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("sid={}, err={}\", sid,"));
        assertFalse(source.contains("Vector 0 matches sid={}\", sid"));
        assertTrue(source.contains("Vector 0 matches sidHash={}"));
    }

    @Test
    void vectorEmptyReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/LangChainRAGService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"vector.retrieval.emptyReason\", emptyReason);"));
        assertTrue(source.contains(
                "TraceStore.put(\"vector.retrieval.emptyReason\", SafeRedactor.traceLabelOrFallback(emptyReason, \"unknown\"));"));
    }

    @Test
    void vectorMetadataParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/LangChainRAGService.java"),
                StandardCharsets.UTF_8);

        assertParserCatchNarrowed(source, "private static double resolveMinScore(Map<String, Object> meta, double def)");
        assertParserCatchNarrowed(source, "private static int metaInt(Map<String, Object> meta, String key, int def)");
    }

    @Test
    void vectorMetadataTopKParserDropsNonFiniteNumbers() throws Exception {
        Method method = LangChainRAGService.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        method.setAccessible(true);

        assertEquals(5, method.invoke(null, Map.of("vectorTopK", Double.POSITIVE_INFINITY), "vectorTopK", 5));
        assertEquals(5, method.invoke(null, Map.of("vectorTopK", Double.NaN), "vectorTopK", 5));
    }

    @Test
    void vectorMetadataParseFallbacksLeaveRedactedTraceBreadcrumbs() throws Exception {
        Method minScore = LangChainRAGService.class.getDeclaredMethod(
                "resolveMinScore", Map.class, double.class);
        minScore.setAccessible(true);
        Method topK = LangChainRAGService.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        topK.setAccessible(true);
        String rawSecret = "ownerToken=raw-vector-parser-secret";

        assertEquals(0.6d, ((Number) minScore.invoke(null, Map.of("vecMinScore", rawSecret), 0.6d)).doubleValue(), 1.0e-9);
        assertEquals(5, topK.invoke(null, Map.of("vectorTopK", rawSecret), "vectorTopK", 5));

        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.minScore.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("vector.retrieval.minScore.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.metaInt.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("vector.retrieval.metaInt.errorType"));
        assertEquals("vectorTopK", TraceStore.get("vector.retrieval.metaInt.key"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawSecret));
    }

    @Test
    void vectorMetadataNumericFallbacksLeaveRedactedTraceBreadcrumbs() throws Exception {
        Method minScore = LangChainRAGService.class.getDeclaredMethod(
                "resolveMinScore", Map.class, double.class);
        minScore.setAccessible(true);
        Method topK = LangChainRAGService.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        topK.setAccessible(true);

        assertEquals(0.6d, ((Number) minScore.invoke(null, Map.of("vecMinScore", Double.NaN), 0.6d)).doubleValue(), 1.0e-9);
        assertEquals(0.6d, ((Number) minScore.invoke(null, Map.of("vecMinScore", 2.0d), 0.6d)).doubleValue(), 1.0e-9);
        assertEquals(5, topK.invoke(null, Map.of("vectorTopK", Double.NEGATIVE_INFINITY), "vectorTopK", 5));

        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.minScore.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("vector.retrieval.minScore.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.metaInt.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("vector.retrieval.metaInt.errorType"));
        assertEquals("vectorTopK", TraceStore.get("vector.retrieval.metaInt.key"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("Infinity"));
    }

    @Test
    void vectorTopKClampLeavesTraceBreadcrumb() throws Exception {
        Method method = LangChainRAGService.class.getDeclaredMethod(
                "resolveTopK", Map.class, int.class);
        method.setAccessible(true);

        assertEquals(50, method.invoke(null, Map.of("vectorTopK", 500), 5));

        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.topK.clamped"));
        assertEquals("max_exceeded", TraceStore.get("vector.retrieval.topK.clampReason"));
        assertEquals("vectorTopK", TraceStore.get("vector.retrieval.topK.key"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
        assertTrue(method.contains("fail-soft stage={} errorType={}")
                        && method.contains("\"invalid_number\""),
                "numeric fallback parser should use stable invalid_number error label: " + signature);
    }
}
