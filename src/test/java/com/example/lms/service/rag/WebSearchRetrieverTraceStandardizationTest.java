package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.extract.PageContentScraper;
import com.example.lms.service.rag.filter.EducationDocClassifier;
import com.example.lms.service.rag.filter.GenericDocClassifier;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSearchRetrieverTraceStandardizationTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void webSearchRetrieverTraceSuppressionsNormalizeNumericErrorType() {
        WebSearchRetrieverTraceSuppressions.trace(
                "metaInt.parse",
                new NumberFormatException("ownerToken=raw-secret"));

        assertEquals("invalid_number", TraceStore.get("webSearch.suppressed.metaInt.parse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("ownerToken=raw-secret"), trace);
    }

    @Test
    void webSearchRetrieverTraceSuppressionsIncludeSafeAggregateStageAndErrorType() {
        String rawStage = "metaInt.parse " + com.example.lms.test.SecretFixtures.openAiKey();

        WebSearchRetrieverTraceSuppressions.trace(
                rawStage,
                new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("webSearch.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("webSearch.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("webSearch.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("webSearch.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }

    @Test
    void disabledReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"webSearch.disabledReason\", disabledReason);"));
        assertTrue(source.contains(
                "TraceStore.put(\"webSearch.disabledReason\", SafeRedactor.traceLabelOrFallback(disabledReason, \"unknown\"));"));
    }

    @Test
    void webSearchRetrieverDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "WebSearchRetriever fail-soft paths need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void webSearchRetrieverRetrievalFallbacksLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertWebSearchStage(source, "multiSearch.supplemental");
        assertWebSearchStage(source, "educationClassifier.filter");
        assertWebSearchStage(source, "providerName");
        assertWebSearchStage(source, "authorityMin.filter");
        assertWebSearchStage(source, "traceWebSearchCounts");
    }

    @Test
    void webSearchRetrieverUtilityFallbacksLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertWebSearchStage(source, "retry.skippedCount");
        assertWebSearchStage(source, "retry.awaitCounters");
        assertWebSearchStage(source, "retry.sleepInterrupted");
        assertWebSearchStage(source, "extractUrl.href");
        assertWebSearchStage(source, "extractUrl.http");
        assertWebSearchStage(source, "serpCache.getCast");
        assertWebSearchStage(source, "urlMatchesAnySite.uri");
        assertWebSearchStage(source, "urlMatchesAnySite.fallback");
    }

    @Test
    void scrapeFailureLogUsesUrlDiagnosticSummary() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.debug(\"[WebSearchRetriever] scrape fail {}"));
        assertFalse(source.contains("log.debug(\"[WebSearchRetriever] scrape fail url={}"));
        assertFalse(source.contains("SafeRedactor.diagnosticValue(\"webSearch.scrape.url\", url"));
        assertTrue(source.contains("scrape fail urlPresent={} urlHash12={} urlLength={}"));
        assertTrue(source.contains("SafeRedactor.hash12(url)"));
        assertTrue(source.contains("url == null ? 0 : url.length()"));
    }

    @Test
    void metadataDebugLogUsesPurposeSummaryOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.debug(\"[WebSearch][meta] purpose={}, keys={}\", meta.get(\"purpose\"), meta.keySet());"));
        assertTrue(source.contains("log.debug(\"[WebSearch][meta] purposeHash12={} purposeLength={} keyCount={}"));
        assertTrue(source.contains("String purposeText = purpose == null ? null : String.valueOf(purpose);"));
        assertTrue(source.contains("SafeRedactor.hash12(purposeText)"));
        assertTrue(source.contains("purposeText == null ? 0 : purposeText.length()"));
        assertTrue(source.contains("meta.size()"));
    }

    @Test
    void metadataNumberParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("catch (Exception ignore) { WebSearchRetrieverTraceSuppressions.trace(\"metaInt.parse\""));
        assertFalse(source.contains("catch (Exception ignore) { WebSearchRetrieverTraceSuppressions.trace(\"metaLong.parse\""));
        assertFalse(source.contains("catch (Exception ignore) { WebSearchRetrieverTraceSuppressions.trace(\"metaDouble.parse\""));
        assertTrue(source.contains("catch (NumberFormatException ignore)"));
        assertTrue(source.contains("WebSearchRetrieverTraceSuppressions.trace(\"metaInt.parse\""));
        assertTrue(source.contains("WebSearchRetrieverTraceSuppressions.trace(\"metaLong.parse\""));
        assertTrue(source.contains("WebSearchRetrieverTraceSuppressions.trace(\"metaDouble.parse\""));
        assertWebSearchInvalidNumberStage(source, "metaInt.parse");
        assertWebSearchInvalidNumberStage(source, "metaLong.parse");
        assertWebSearchInvalidNumberStage(source, "metaDouble.parse");
    }

    @Test
    void metadataNumberParsersDropNonFiniteNumbers() throws Exception {
        Method metaInt = WebSearchRetriever.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        Method metaLong = WebSearchRetriever.class.getDeclaredMethod(
                "metaLong", Map.class, String.class, long.class);
        Method metaDouble = WebSearchRetriever.class.getDeclaredMethod(
                "metaDouble", Map.class, String.class, double.class);
        Method metaBool = WebSearchRetriever.class.getDeclaredMethod(
                "metaBool", Map.class, String.class, boolean.class);
        metaInt.setAccessible(true);
        metaLong.setAccessible(true);
        metaDouble.setAccessible(true);
        metaBool.setAccessible(true);

        Map<String, Object> meta = Map.of(
                "topK", Double.POSITIVE_INFINITY,
                "timeoutMs", Double.NEGATIVE_INFINITY,
                "minScore", Double.NaN,
                "enabled", Double.POSITIVE_INFINITY,
                "stringScore", "Infinity");
        assertEquals(5, metaInt.invoke(null, meta, "topK", 5));
        assertEquals(1200L, metaLong.invoke(null, meta, "timeoutMs", 1200L));
        assertEquals(0.25d, (Double) metaDouble.invoke(null, meta, "minScore", 0.25d), 0.0d);
        assertEquals(0.25d, (Double) metaDouble.invoke(null, meta, "stringScore", 0.25d), 0.0d);
        assertFalse((Boolean) metaBool.invoke(null, meta, "enabled", false));
    }

    @Test
    void preprocessorFailureLogUsesStructuredSafeDiagnostics() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("[WebSearchRetriever] preprocessor failed: {}"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains(
                "[AWX][rag][web] preprocessor failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(source.contains("\"web-preprocessor-error\""));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hash12(normalized)"));
        assertTrue(source.contains("normalized == null ? 0 : normalized.length()"));
    }

    private static void assertWebSearchStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[WebSearchRetriever] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing WebSearchRetriever fail-soft stage: " + stage);
    }

    private static void assertWebSearchInvalidNumberStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[WebSearchRetriever] fail-soft stage={} errorType={}\",")
                        && source.contains("\"" + stage + "\", \"invalid_number\""),
                () -> "missing WebSearchRetriever invalid_number fail-soft stage: " + stage);
    }

    @Test
    void authorityFilterStarvationBridgesCanonicalWebSearchAndRagCounts() {
        TraceStore.clear();
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenReturn(List.of("alpha evidence", "beta evidence", "gamma evidence"));
        when(provider.getName()).thenReturn("test");

        AuthorityScorer authorityScorer = mock(AuthorityScorer.class);
        when(authorityScorer.weightFor(anyString())).thenReturn(0.0d);
        GenericDocClassifier genericClassifier = mock(GenericDocClassifier.class);
        GameDomainDetector domainDetector = mock(GameDomainDetector.class);
        when(domainDetector.detect(anyString())).thenReturn("GENERAL");

        WebSearchRetriever retriever = new WebSearchRetriever(
                provider,
                null,
                mock(PageContentScraper.class),
                authorityScorer,
                genericClassifier,
                domainDetector,
                mock(EducationDocClassifier.class));

        List<?> result = retriever.retrieve(QueryUtils.buildQuery("bridge starvation", Map.of(
                "web.authorityMin", 0.8d,
                "web.authorityMin.strict", true,
                "webTopK", 3)));

        assertTrue(result.isEmpty());
        assertEquals(3, TraceStore.get("webSearch.returnedCount"));
        assertEquals(0, TraceStore.get("webSearch.afterFilterCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("webSearch.afterFilterStarved"));
        assertEquals(3, TraceStore.get("rag.returnedCount"));
        assertEquals(0, TraceStore.get("rag.afterFilterCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("rag.afterFilterStarved"));
    }
}
