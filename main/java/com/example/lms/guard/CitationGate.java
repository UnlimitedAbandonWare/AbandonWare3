package com.example.lms.guard;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Citation gate that checks minimum count and (optionally) requires official sources tier.
 * This is a passive utility not tied to any framework.
 */
@Component("legacyCitationGate")
public class CitationGate {

    @Value("${guard.citation.min_count:2}")
    private int minCount = 2;

    @Value("${guard.citation.require_official:true}")
    private boolean requireOfficial = true;

    public CitationGate() {
    }

    public CitationGate(int minCount, boolean requireOfficial) {
        this.minCount = Math.max(0, minCount);
        this.requireOfficial = requireOfficial;
    }

    public boolean check(List<String> sources, List<String> official) {
        int sourceCount = sources == null ? 0 : sources.size();
        int officialCount = official == null ? 0 : official.size();
        if (sources == null) {
            traceDecision(false, "missing_sources", sourceCount, officialCount);
            return false;
        }
        if (sourceCount < minCount) {
            traceDecision(false, "insufficient_sources", sourceCount, officialCount);
            return false;
        }
        if (requireOfficial && officialCount == 0) {
            traceDecision(false, "official_required", sourceCount, officialCount);
            return false;
        }
        traceDecision(true, "pass", sourceCount, officialCount);
        return true;
    }

    private void traceDecision(boolean pass, String reason, int sourceCount, int officialCount) {
        TraceStore.put("guard.legacyCitation.pass", pass);
        TraceStore.put("guard.legacyCitation.reason", reason == null || reason.isBlank() ? "unknown" : reason);
        TraceStore.put("guard.legacyCitation.sourceCount", Math.max(0, sourceCount));
        TraceStore.put("guard.legacyCitation.officialCount", Math.max(0, officialCount));
        TraceStore.put("guard.legacyCitation.requiredCount", Math.max(0, minCount));
        TraceStore.put("guard.legacyCitation.requireOfficial", requireOfficial);
        TraceStore.put("gate.citation.count", Math.max(0, sourceCount));
        TraceStore.put("gate.citation.passed", pass);
        TraceStore.put("gate.hypernova.override", false);
    }
}
