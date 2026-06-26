package com.abandonware.ai.agent.integrations;

import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.RankingParams;
import com.acme.aicore.domain.model.RerankParams;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.RankingPort;
import com.acme.aicore.domain.ports.WebSearchProvider;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AcmeAICoreGatewayTraceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void providerFailureKeepsFanoutFailSoftAndRecordsRedactedBreadcrumb() {
        String unsafeProviderId = "private-provider fake-sensitive-token";
        AcmeAICoreGateway gateway = new AcmeAICoreGateway(List.of(
                failingProvider(unsafeProviderId),
                okProvider()), rankingPort());

        List<Map<String, Object>> out = gateway.searchAndRank("query", 3, "ko");

        assertEquals(1, out.size());
        assertEquals("ok-1", out.get(0).get("id"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.acmeGateway.providerFailure"));
        assertEquals("provider.search", TraceStore.get("agent.acmeGateway.providerFailure.stage"));
        assertEquals("IllegalStateException", TraceStore.get("agent.acmeGateway.providerFailure.errorClass"));
        assertEquals(unsafeProviderId.length(), TraceStore.get("agent.acmeGateway.providerFailure.providerIdLength"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains("private-provider"), rendered);
        assertFalse(rendered.contains("fake-sensitive-token"), rendered);
    }

    @Test
    void rankingFailureReturnsEmptyWithRedactedBreadcrumb() {
        AcmeAICoreGateway gateway = new AcmeAICoreGateway(List.of(okProvider()), failingRankingPort());

        List<Map<String, Object>> out = gateway.searchAndRank("private ranking query", 3, "ko");

        assertEquals(List.of(), out);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.acmeGateway.rankingFailure"));
        assertEquals("ranking.fuseAndRank", TraceStore.get("agent.acmeGateway.rankingFailure.stage"));
        assertEquals("IllegalStateException", TraceStore.get("agent.acmeGateway.rankingFailure.errorClass"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains("private ranking query"), rendered);
        assertFalse(rendered.contains("fake-sensitive-token"), rendered);
    }

    private static WebSearchProvider failingProvider(String id) {
        return new WebSearchProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Mono<SearchBundle> search(WebSearchQuery query) {
                return Mono.error(new IllegalStateException("secret query fake-sensitive-token"));
            }
        };
    }

    private static WebSearchProvider okProvider() {
        return new WebSearchProvider() {
            @Override
            public String id() {
                return "ok";
            }

            @Override
            public Mono<SearchBundle> search(WebSearchQuery query) {
                return Mono.just(new SearchBundle("web", List.of(
                        new SearchBundle.Doc("ok-1", "Title", "Snippet", "https://example.test", "2026-06-14"))));
            }
        };
    }

    private static RankingPort rankingPort() {
        return new RankingPort() {
            @Override
            public Mono<List<RankedDoc>> fuseAndRank(List<SearchBundle> bundles, RankingParams params) {
                return Mono.just(List.of(RankedDoc.of("ok-1", 0.9d)));
            }

            @Override
            public Mono<List<RankedDoc>> rerank(List<RankedDoc> topN, RerankParams params) {
                return Mono.just(topN);
            }
        };
    }

    private static RankingPort failingRankingPort() {
        return new RankingPort() {
            @Override
            public Mono<List<RankedDoc>> fuseAndRank(List<SearchBundle> bundles, RankingParams params) {
                return Mono.error(new IllegalStateException("private ranking fake-sensitive-token"));
            }

            @Override
            public Mono<List<RankedDoc>> rerank(List<RankedDoc> topN, RerankParams params) {
                return Mono.just(topN);
            }
        };
    }
}
