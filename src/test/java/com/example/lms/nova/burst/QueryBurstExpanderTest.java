package com.example.lms.nova.burst;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.SelfAskPlanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryBurstExpanderTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void koreanPrimaryMixedQueryLimitsEnglishSuffixFlood() {
        QueryBurstExpander expander = new QueryBurstExpander();
        String base = "Galaxy 트라이폴드 루머";

        List<String> variants = expander.expand(base, 3, 16);

        Set<String> englishSuffixes = Set.of(
                " official", " announcement", " release", " release date",
                " price", " specs", " review", " rumor", " vs");
        long englishSuffixCount = variants.stream()
                .filter(v -> v.startsWith(base + " "))
                .filter(v -> englishSuffixes.stream().anyMatch(v::endsWith))
                .count();
        assertTrue(englishSuffixCount <= 2, variants::toString);
        assertTrue(variants.stream().anyMatch(v -> v.startsWith(base + " ") && v.endsWith(" 공식")), variants::toString);
    }
    @Test
    void hangulQueryGetsCleanKoreanVariants() {
        QueryBurstExpander expander = new QueryBurstExpander();
        String base = "\uAC24\uB7ED\uC2DC \uD2B8\uB77C\uC774\uD3F4\uB4DC \uB8E8\uBA38";

        List<String> variants = expander.expand(base, 3, 8);

        assertTrue(variants.stream().anyMatch(v -> v.equals(base + " \uACF5\uC2DD")), variants::toString);
        assertTrue(variants.stream().anyMatch(v -> v.equals(base + " \uCD9C\uC2DC")), variants::toString);
        assertTrue(variants.stream().anyMatch(v -> v.equals("Galaxy trifold rumor")), variants::toString);
    }

    @Test
    void plannerBackedExtremeZExpansionDeduplicatesAndTracesCanonicalKeys() {
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.plan("RAG evidence", 4))
                .thenReturn(List.of(" RAG official ", "RAG official", "", "RAG pdf"));
        QueryBurstExpander expander = new QueryBurstExpander(planner);

        List<String> variants = expander.expand(" RAG evidence ", 2, 4);

        assertEquals(List.of("RAG official", "RAG pdf"), variants);
        assertEquals(2, TraceStore.get("extremeZ.burstExpand.count"));
        assertEquals(2, TraceStore.get("extremeZ.burstExpand.min"));
        assertEquals(4, TraceStore.get("extremeZ.burstExpand.max"));
        assertEquals("", TraceStore.get("extremeZ.burstExpand.bypassReason"));
        verify(planner).plan("RAG evidence", 4);
    }
}
