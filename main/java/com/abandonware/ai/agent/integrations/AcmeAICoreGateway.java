package com.abandonware.ai.agent.integrations;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.acme.aicore.domain.ports.WebSearchProvider;
import com.acme.aicore.domain.ports.RankingPort;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.RankingParams;
import java.util.*;
import java.util.stream.Collectors;





@Component
@ConditionalOnBean(RankingPort.class)
public class AcmeAICoreGateway implements WebSearchGateway {

    private final List<WebSearchProvider> providers;
    private final RankingPort ranking;

    @Autowired
    public AcmeAICoreGateway(List<WebSearchProvider> providers, RankingPort ranking) {
        this.providers = providers;
        this.ranking = ranking;
    }

    @Override
    public List<Map<String, Object>> searchAndRank(String query, int topK, String lang) {
        List<SearchBundle> bundles = new ArrayList<>();
        for (WebSearchProvider p : providers) {
            try {
                var bundle = p.search(new WebSearchQuery(query)).block();
                if (bundle != null) bundles.add(bundle);
            } catch (Exception e) {
                traceSuppressed(p, e);
                // skip provider on error
            }
        }
        if (bundles.isEmpty()) return List.of();

        List<RankedDoc> ranked;
        try {
            ranked = ranking.fuseAndRank(bundles, RankingParams.defaults()).block();
        } catch (RuntimeException e) {
            traceRankingSuppressed(e);
            return List.of();
        }
        if (ranked == null) return List.of();

        Map<String, SearchBundle.Doc> byId = bundles.stream()
                .flatMap(b -> b.docs().stream())
                .collect(Collectors.toMap(SearchBundle.Doc::id, d -> d, (a,b)->a));

        List<Map<String,Object>> out = new ArrayList<>();
        for (RankedDoc rd : ranked) {
            var doc = byId.get(rd.id());
            if (doc != null) {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("id", doc.id());
                m.put("title", doc.title());
                m.put("snippet", doc.snippet());
                m.put("url", doc.url());
                m.put("publishedAt", doc.publishedAt());
                m.put("score", rd.score());
                out.add(m);
                if (out.size() >= Math.max(1, topK)) break;
            }
        }
        return out;
    }

    private static void traceSuppressed(WebSearchProvider provider, Throwable error) {
        String providerId = provider == null ? "" : provider.id();
        TraceStore.put("agent.acmeGateway.providerFailure", true);
        TraceStore.put("agent.acmeGateway.providerFailure.stage", "provider.search");
        TraceStore.put("agent.acmeGateway.providerFailure.errorClass",
                error == null ? "unknown" : error.getClass().getSimpleName());
        TraceStore.put("agent.acmeGateway.providerFailure.providerIdHash", SafeRedactor.hashValue(providerId));
        TraceStore.put("agent.acmeGateway.providerFailure.providerIdLength", providerId.length());
    }

    private static void traceRankingSuppressed(Throwable error) {
        TraceStore.put("agent.acmeGateway.rankingFailure", true);
        TraceStore.put("agent.acmeGateway.rankingFailure.stage", "ranking.fuseAndRank");
        TraceStore.put("agent.acmeGateway.rankingFailure.errorClass",
                error == null ? "unknown" : error.getClass().getSimpleName());
    }
}
