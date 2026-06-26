package com.example.lms.service;

import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NaverSearchServiceRedactionContractTest {

    @Test
    void outboundHeadersDoNotCarryInternalKeyLabels() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("\"X-Key-Label\""));
        assertTrue(source.contains("\"X-Naver-Client-Id\""));
        assertTrue(source.contains("\"X-Naver-Client-Secret\""));
    }

    @Test
    void webkrRequestsDoNotSendUnsupportedSortParam() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains(".queryParam(\"sort\""));
        assertFalse(source.contains("sort=date"));
    }

    @Test
    void naverSearchServiceDoesNotUseExactEmptyCatchBlocks() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        long exactEmptyCatchBlocks = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();

        assertEquals(0L, exactEmptyCatchBlocks);
    }

    @Test
    void naverDisplayClampStaysInsideVendorBounds() {
        assertEquals(10, NaverSearchService.clampNaverDisplay(0));
        assertEquals(10, NaverSearchService.clampNaverDisplay(9));
        assertEquals(20, NaverSearchService.clampNaverDisplay(20));
        assertEquals(100, NaverSearchService.clampNaverDisplay(101));
        assertEquals(100, NaverSearchService.clampNaverDisplay(500));
    }

    @Test
    void errorBodyDiagnosticsAreRedacted() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertTrue(source.contains("\"bodyHash\""));
        assertTrue(source.contains("\"bodyLength\""));
        assertFalse(source.contains("bodyPreview"));
        assertFalse(source.contains("SafeRedactor.safeMessage(body == null ? \"\" : body, 512)"));
        assertFalse(source.contains("String cut = (body != null && body.length() > 512)"));
        assertFalse(source.contains("body={}\""));
    }

    @Test
    void sensitiveHeaderDiagnosticsDoNotExposeMaskedTails() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertTrue(source.contains("present=true"));
        assertFalse(source.contains("private static String mask("));
        assertFalse(source.contains("substring(s.length() - 4)"));
    }

    @Test
    void providerFailureLogsDoNotRenderThrowableToString() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("e.toString()"));
        assertFalse(source.contains("t.toString()"));
        assertFalse(source.contains("ex.toString()"));
        assertFalse(source.contains("searchWithTraceSync failed: {}"));
        assertFalse(source.contains("Naver API {} failed: {}"));
        assertFalse(source.contains("log.error(\"Naver API call failed\", ex)"));
        assertTrue(source.contains("failureReason={} errorType={} queryHash={} queryLength={}"));
    }

    @Test
    void naverTraceSuppressionsNormalizeNumericErrorType() {
        TraceStore.clear();

        NaverTraceSuppressions.trace("retryAfter.parse", new NumberFormatException("ownerToken=secret"));

        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.suppressed.retryAfter.parse"));
        assertEquals("invalid_number", TraceStore.get("web.naver.suppressed.retryAfter.parse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"));
        assertFalse(trace.contains("ownerToken=secret"));
    }

    @Test
    void queryTransformerFailureLogsUseHashAndLengthOnly() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("queryTransformer.transformEnhanced failed: {}\", e.getMessage())"));
        assertFalse(source.contains("queryTransformer.transform failed: {}\", e.getMessage())"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(source.contains("queryTransformer.transformEnhanced failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("queryTransformer.transform failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length()"));
    }

    @Test
    void traceStoreCorrelationIdsAreHashOnly() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("TraceStore.put(\"sid\", sessionId);"));
        assertFalse(source.contains("TraceStore.put(\"trace.id\", requestId);"));
        assertTrue(source.contains("TraceStore.put(\"sid\", SafeRedactor.hashValue(sessionId));"));
        assertTrue(source.contains("TraceStore.put(\"trace.id\", SafeRedactor.hashValue(requestId));"));
    }

    @Test
    void preprocessorAndCacheOnlyFailuresLeaveRedactedTraceTypes() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertTrue(source.contains("TraceStore.put(\"web.naver.preprocessor.failed\", true);"));
        assertTrue(source.contains("TraceStore.put(\"web.naver.preprocessor.failureReason\""));
        assertTrue(source.contains("TraceStore.put(\"web.naver.cacheOnly.errorType\""));
        assertFalse(source.contains("TraceStore.put(\"web.naver.preprocessor.query\", original);"));
        assertFalse(source.contains("TraceStore.put(\"web.naver.cacheOnly.errorType\", t.toString());"));
    }

    @Test
    void webSearchProviderOverrideUsesGuardedSyncFacade() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));
        String body = methodBody(source,
                "public SearchResult searchWithTrace(String query, int topK)",
                "    /**");

        assertFalse(body.contains(".block(Duration.ofSeconds(5))"));
        assertTrue(body.contains("return searchWithTraceSync(query, topK, Duration.ofSeconds(5));"));
    }

    @Test
    void assistantAnswerSnippetFacadeUsesFailSoftBlockGuard() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));
        String body = methodBody(source,
                "public List<String> searchSnippets(String userPrompt,",
                "    /** Trace");

        assertTrue(body.contains("try {"));
        assertTrue(body.contains("traceNaverFailure(userPrompt, topK"));
        assertTrue(body.contains("failureReason={} errorType={} queryHash={} queryLength={} timeoutMs={}"));
        assertTrue(body.contains("return List.of();"));
        assertTrue(body.contains(".blockOptional(timeout)"));
        assertFalse(body.contains(".blockOptional(Duration.ofSeconds(5))"));
    }

    @Test
    void snippetReinforcementFailureLogUsesHashAndLengthOnly() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("Failed to reinforce snippet: {}\", e.getMessage())"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(source.contains("Failed to reinforce snippet. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length()"));
    }

    @Test
    void localRagFailureLogUsesHashAndLengthOnly() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("Local RAG retrieval failed: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("Local RAG retrieval failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
    }

    @Test
    void sensitiveHeaderLogValueOnlyReportsPresence() throws Exception {
        String rendered = headerLogValue("X-Naver-Client-Secret", List.of("secret-value-1234567890"));

        assertEquals("present=true", rendered);
        assertFalse(rendered.contains("secret-value"));
        assertFalse(rendered.contains("7890"));
        assertFalse(rendered.contains("20"));
    }

    @Test
    void sensitiveHeaderLogValuePreservesMissingSignalWithoutValueShape() throws Exception {
        String rendered = headerLogValue("Authorization", List.of("", "Bearer " + "secret-value-1234567890"));

        assertEquals("present=false, present=true", rendered);
        assertFalse(rendered.contains("Bearer"));
        assertFalse(rendered.contains("secret-value"));
    }

    @Test
    void explicitMalformedOrPartialNaverKeysFallBackToClientPair() {
        NaverSearchService malformed = naverService("only-client-id", "fallback-id", "fallback-secret");
        NaverSearchService partial = naverService("client-id:", "fallback-id", "fallback-secret");

        assertEquals(1, parsedNaverKeyCount(malformed));
        assertEquals(1, parsedNaverKeyCount(partial));
    }

    @Test
    void explicitNaverKeysParseFailureHasConstructorBridgeFallback() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertTrue(source.contains("using client-id/secret bridge after naver.keys parse failure"));
        assertTrue(source.contains("fallbackUsed=true"));
    }

    @Test
    void blankNaverKeysUsesClientPairBridgeOnlyWhenBothPartsExist() {
        NaverSearchService bridged = naverService("", "fallback-id", "fallback-secret");
        NaverSearchService missingSecret = naverService("", "fallback-id", "");

        assertEquals(1, parsedNaverKeyCount(bridged));
        assertNoParsedNaverKeys(missingSecret);
    }

    @Test
    void quotedCommaNaverKeysParsesAsSingleExplicitPair() {
        NaverSearchService service = naverService("\"primary-id,primary-secret\"", "fallback-id", "fallback-secret");

        assertEquals(1, parsedNaverKeyCount(service));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NaverSearchService naverService(String naverKeys, String clientId, String clientSecret) {
        return new NaverSearchService(
                null,
                null,
                null,
                mock(dev.langchain4j.store.embedding.EmbeddingStore.class),
                mock(dev.langchain4j.model.embedding.EmbeddingModel.class),
                () -> 1L,
                null,
                naverKeys,
                clientId,
                clientSecret,
                10L,
                1L,
                mock(PlatformTransactionManager.class),
                new RateLimitPolicy(),
                WebClient.builder().baseUrl("https://openapi.naver.com").build(),
                null,
                null);
    }

    private static String headerLogValue(String name, List<String> values) throws Exception {
        Method method = NaverSearchService.class.getDeclaredMethod("safeHeaderValueForLog", String.class, List.class);
        method.setAccessible(true);
        return (String) method.invoke(null, name, values);
    }

    private static String methodBody(String source, String startMarker, String nextMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing source marker: " + startMarker);
        int end = source.indexOf(nextMarker, start + startMarker.length());
        assertTrue(end > start, "missing next marker: " + nextMarker);
        return source.substring(start, end);
    }

    private static void assertNoParsedNaverKeys(NaverSearchService service) {
        Collection<?> parsedKeys = (Collection<?>) ReflectionTestUtils.getField(service, "naverKeys");
        assertTrue(parsedKeys == null || parsedKeys.isEmpty());
        assertEquals(0, parsedNaverKeyCount(service));
    }

    private static int parsedNaverKeyCount(NaverSearchService service) {
        Object count = ReflectionTestUtils.getField(service, "parsedNaverKeyCount");
        return count instanceof Number number ? number.intValue() : -1;
    }
}
