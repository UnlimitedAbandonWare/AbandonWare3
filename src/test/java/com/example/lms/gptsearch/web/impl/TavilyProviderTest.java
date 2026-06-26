package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.TavilyWebSearchRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TavilyProviderTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void disabledRetrieverProducesProviderDisabledTraceWithoutExternalCall() {
        TraceStore.clear();
        TavilyProvider provider = new TavilyProvider();
        String rawQuery = "private tavily bridge query";

        var result = provider.search(new WebSearchQuery(
                rawQuery,
                3,
                List.of(ProviderId.TAVILY),
                Duration.ofDays(1)
        ));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals("tavily.enabled=false", TraceStore.get("web.tavily.disabledReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.skipped"));
        assertEquals("tavily.enabled=false", TraceStore.get("web.tavily.skipped.reason"));
        assertEquals("provider-disabled", TraceStore.get("web.tavily.failureReason"));
        assertEquals(3, TraceStore.get("web.tavily.requestedCount"));
        assertTrue(String.valueOf(TraceStore.get("web.tavily.queryHash")).startsWith("hash:"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    @Test
    void disabledRetrieverClearsStaleProviderFailureResidue() {
        TraceStore.clear();
        TraceStore.put("web.tavily.timeout", true);
        TraceStore.put("web.tavily.cancelled", true);
        TraceStore.put("web.tavily.httpStatus", 429);
        TraceStore.put("web.tavily.429", true);
        TraceStore.put("web.tavily.rateLimited", true);
        TavilyProvider provider = new TavilyProvider();
        String rawQuery = "private tavily disabled residue query";

        var result = provider.search(new WebSearchQuery(
                rawQuery,
                3,
                List.of(ProviderId.TAVILY),
                Duration.ofDays(1)
        ));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.cancelled"));
        assertEquals(null, TraceStore.get("web.tavily.httpStatus"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.429"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.rateLimited"));
        assertEquals("provider-disabled", TraceStore.get("web.tavily.failureReason"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    @Test
    void delegatesToExistingRetrieverAndMapsContentMetadataToWebDocuments() {
        TraceStore.clear();
        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", new ReturningRetriever());

        var result = provider.search(new WebSearchQuery(
                "current spring boot docs?",
                2,
                List.of(ProviderId.TAVILY),
                null
        ));

        assertEquals(1, result.getDocuments().size());
        assertEquals("Tavily Result", result.getDocuments().get(0).getTitle());
        assertEquals("https://example.com/tavily", result.getDocuments().get(0).getUrl());
        assertEquals("retrieved tavily snippet", result.getDocuments().get(0).getSnippet());
        assertEquals("TavilyWebSearchRetriever", TraceStore.get("web.tavily.providerBridge"));
        assertEquals(1, TraceStore.get("web.tavily.bridgeDocumentCount"));
    }

    @Test
    void successfulDelegateClearsStaleProviderFailureResidue() {
        TraceStore.clear();
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.providerEmpty", true);
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.failureReason", "timeout");
        TraceStore.put("web.tavily.timeout", true);
        TraceStore.put("web.tavily.cancelled", true);
        TraceStore.put("web.tavily.httpStatus", 429);
        TraceStore.put("web.tavily.429", true);
        TraceStore.put("web.tavily.rateLimited", true);
        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", new ReturningRetriever());

        var result = provider.search(new WebSearchQuery(
                "current spring boot docs?",
                2,
                List.of(ProviderId.TAVILY),
                null
        ));

        assertEquals(1, result.getDocuments().size());
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.providerEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.zeroResults"));
        assertEquals("", TraceStore.get("web.tavily.failureReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.cancelled"));
        assertEquals(null, TraceStore.get("web.tavily.httpStatus"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.429"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.rateLimited"));
    }

    @Test
    void emptyDelegateWithoutFailureReasonGetsProviderEmptyBridgeFallback() {
        TraceStore.clear();
        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", new EmptyRetriever());
        String rawQuery = "private tavily bridge empty query";

        var result = provider.search(new WebSearchQuery(
                rawQuery,
                2,
                List.of(ProviderId.TAVILY),
                null
        ));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals("TavilyWebSearchRetriever", TraceStore.get("web.tavily.providerBridge"));
        assertEquals(0, TraceStore.get("web.tavily.bridgeDocumentCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerEmpty"));
        assertEquals("provider-empty", TraceStore.get("web.tavily.failureReason"));
        assertEquals(2, TraceStore.get("web.tavily.requestedCount"));
        assertEquals(0, TraceStore.get("web.tavily.returnedCount"));
        assertEquals(0, TraceStore.get("web.tavily.afterFilterCount"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    @Test
    void emptyDelegateClearsStaleProviderFailureResidue() {
        TraceStore.clear();
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.failureReason", "timeout");
        TraceStore.put("web.tavily.timeout", true);
        TraceStore.put("web.tavily.cancelled", true);
        TraceStore.put("web.tavily.httpStatus", 429);
        TraceStore.put("web.tavily.429", true);
        TraceStore.put("web.tavily.rateLimited", true);
        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", new EmptyRetriever());
        String rawQuery = "private tavily empty residue query";

        var result = provider.search(new WebSearchQuery(
                rawQuery,
                2,
                List.of(ProviderId.TAVILY),
                null
        ));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.cancelled"));
        assertEquals(null, TraceStore.get("web.tavily.httpStatus"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.429"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.rateLimited"));
        assertEquals("provider-empty", TraceStore.get("web.tavily.failureReason"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    @Test
    void existingRetrieverPreservesTavilyTitleAndUrlMetadataForProviderBridge() {
        TraceStore.clear();
        String rawQuery = "private tavily metadata bridge query";
        String apiKey = "tvly-test-metadata-key";
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"results":[{"title":"Tavily Credits","url":"https://docs.tavily.com/documentation/api-credits","content":"monthly credits snippet"}]}
                                """)
                        .build())));
        ReflectionTestUtils.setField(retriever, "apiKey", apiKey);
        ReflectionTestUtils.setField(retriever, "baseUrl", "https://api.tavily.com/search");
        ReflectionTestUtils.setField(retriever, "maxResults", 2);
        ReflectionTestUtils.setField(retriever, "timeoutMs", 2000);

        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", retriever);

        var result = provider.search(new WebSearchQuery(
                rawQuery,
                2,
                List.of(ProviderId.TAVILY),
                null
        ));

        assertEquals(1, result.getDocuments().size());
        assertEquals("Tavily Credits", result.getDocuments().get(0).getTitle());
        assertEquals("https://docs.tavily.com/documentation/api-credits", result.getDocuments().get(0).getUrl());
        assertTrue(result.getDocuments().get(0).getSnippet().contains("monthly credits snippet"));
        assertEquals(1, TraceStore.get("web.tavily.returnedCount"));
        assertEquals(1, TraceStore.get("web.tavily.afterFilterCount"));
        assertEquals("TavilyWebSearchRetriever", TraceStore.get("web.tavily.providerBridge"));
        assertEquals(1, TraceStore.get("web.tavily.bridgeDocumentCount"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
        assertFalse(TraceStore.getAll().toString().contains(apiKey));
    }

    @Test
    void missingApiKeyUsesCanonicalProviderDisabledReason() {
        TraceStore.clear();
        String rawQuery = "private tavily missing key query";
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder()
                .exchangeFunction(request -> {
                    throw new AssertionError("missing api key must not call Tavily");
                }));
        ReflectionTestUtils.setField(retriever, "apiKey", "");
        ReflectionTestUtils.setField(retriever, "maxResults", 2);

        var result = retriever.retrieve(new Query(rawQuery));

        assertTrue(result.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.disabledReason"));
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.disabledReasonCanonical"));
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.skipped.reason"));
        assertEquals("provider-disabled", TraceStore.get("web.tavily.failureReason"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    private static final class ReturningRetriever extends TavilyWebSearchRetriever {
        ReturningRetriever() {
            super(WebClient.builder());
        }

        @Override
        public List<Content> retrieve(Query query) {
            return List.of(Content.from(TextSegment.from(
                    "retrieved tavily snippet",
                    Metadata.from(Map.of(
                            "title", "Tavily Result",
                            "url", "https://example.com/tavily"
                    ))
            )));
        }
    }

    private static final class EmptyRetriever extends TavilyWebSearchRetriever {
        EmptyRetriever() {
            super(WebClient.builder());
        }

        @Override
        public List<Content> retrieve(Query query) {
            return List.of();
        }
    }
}
