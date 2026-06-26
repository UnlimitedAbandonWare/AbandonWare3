package com.example.lms.integration.handlers;

import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.RelevanceScoringService;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.extract.PageContentScraper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AdaptiveWebSearchHandlerProviderTraceTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void tracesRequestedRegisteredResolvedAndMissingProvidersWithoutRawQuery() {
        TraceStore.clear();
        AdaptiveWebSearchHandler handler = new AdaptiveWebSearchHandler(
                new SearchDecisionService(),
                List.of(new EmptyProvider(ProviderId.TAVILY)),
                mock(PageContentScraper.class),
                mock(RelevanceScoringService.class),
                mock(DomainProfileLoader.class)
        );

        Query query = QueryUtils.buildQuery("private provider trace query",
                Map.of(
                        "useWebSearch", true,
                        "searchMode", "FORCE_LIGHT",
                        "webProviders", "GOOGLECSE,TAVILY"
                ));

        handler.handle(query, new ArrayList<Content>());

        assertEquals(List.of("GOOGLECSE", "TAVILY"), TraceStore.get("web.adaptive.requestedProviders"));
        assertEquals(List.of("TAVILY"), TraceStore.get("web.adaptive.registeredProviders"));
        assertEquals(List.of("TAVILY"), TraceStore.get("web.adaptive.resolvedProviders"));
        assertEquals(List.of("GOOGLECSE"), TraceStore.get("web.adaptive.missingProviders"));
        assertEquals(2, TraceStore.get("web.adaptive.requestedProviderCount"));
        assertEquals(1, TraceStore.get("web.adaptive.missingProviderCount"));
    }

    @Test
    void searchModeMetadataIsTrimmedAndCaseInsensitive() {
        TraceStore.clear();
        AdaptiveWebSearchHandler handler = new AdaptiveWebSearchHandler(
                new SearchDecisionService(),
                List.of(new EmptyProvider(ProviderId.TAVILY)),
                mock(PageContentScraper.class),
                mock(RelevanceScoringService.class),
                mock(DomainProfileLoader.class)
        );

        Query query = QueryUtils.buildQuery("private provider trace query",
                Map.of(
                        "useWebSearch", true,
                        "searchMode", " force_light ",
                        "webProviders", "TAVILY"
                ));

        handler.handle(query, new ArrayList<Content>());

        assertEquals(List.of("TAVILY"), TraceStore.get("web.adaptive.requestedProviders"));
        assertEquals(List.of("TAVILY"), TraceStore.get("web.adaptive.resolvedProviders"));
    }

    @Test
    void invalidMetaIntegerAndProviderIdLeaveRedactedTraceBreadcrumbs() {
        TraceStore.clear();
        AdaptiveWebSearchHandler handler = new AdaptiveWebSearchHandler(
                new SearchDecisionService(),
                List.of(new EmptyProvider(ProviderId.TAVILY)),
                mock(PageContentScraper.class),
                mock(RelevanceScoringService.class),
                mock(DomainProfileLoader.class)
        );

        Query query = QueryUtils.buildQuery("private adaptive parser query",
                Map.of(
                        "useWebSearch", true,
                        "searchMode", "FORCE_LIGHT",
                        "webTopK", "not-a-number",
                        "webProviders", "not-a-provider"
                ));

        handler.handle(query, new ArrayList<Content>());

        assertEquals(Boolean.TRUE, TraceStore.get("web.adaptive.suppressed.metaInt"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.adaptive.suppressed.providerId"));
    }

    @Test
    void failSoftCatchSitesUseRedactedTraceBreadcrumbs() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/com/example/lms/integration/handlers/AdaptiveWebSearchHandler.java"));

        assertEquals(1, count(source, "traceSuppressed(\"domainProfile\", ignore)"));
        assertEquals(1, count(source, "traceSuppressed(\"financeUri\", e)"));
        assertEquals(1, count(source, "traceSuppressed(\"financeFilter\", ignore)"));
        assertEquals(1, count(source, "traceSuppressed(\"precisionFetch\", ex)"));
        assertEquals(1, count(source, "traceSuppressed(\"snippetFetch\", ignore)"));
        assertEquals(1, count(source, "traceSuppressed(\"providerTrace\", ignore)"));
    }

    private static long count(String source, String needle) {
        return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(needle))
                .matcher(source)
                .results()
                .count();
    }

    private record EmptyProvider(ProviderId id) implements WebSearchProvider {
        @Override
        public WebSearchResult search(WebSearchQuery query) {
            return new WebSearchResult(id.name(), List.of());
        }
    }
}
