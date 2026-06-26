package com.example.lms.gptsearch.decision;

import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchDecisionServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void unknownProviderIdsAreSkipped() {
        SearchDecision decision = new SearchDecisionService()
                .decide("RAG?", SearchMode.FORCE_LIGHT, List.of("NAVER", "unknown-ownerToken-secret"), 3);

        assertEquals(List.of(ProviderId.NAVER), decision.providers());
        assertEquals(Boolean.TRUE, TraceStore.get("search.decision.suppressed.searchDecision.providerId"));
        assertEquals("searchDecision.providerId", TraceStore.get("search.decision.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("search.decision.suppressed.errorType"));
        assertEquals("IllegalArgumentException",
                TraceStore.get("search.decision.suppressed.searchDecision.providerId.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("unknown-ownerToken-secret"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void unknownProviderFallbackLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/gptsearch/decision/SearchDecisionService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"searchDecision.providerId\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"search.decision.suppressed.\" + safeStage, true);"));
    }
}
