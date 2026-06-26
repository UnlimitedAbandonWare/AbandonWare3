package com.example.lms.service.disambiguation;

import com.example.lms.prompt.DisambiguationPromptBuilder;
import com.example.lms.search.NoiseClipper;
import com.example.lms.search.TraceStore;
import com.example.lms.service.correction.DomainTermDictionary;
import com.example.lms.service.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QueryDisambiguationServiceQueryRewriteFallbackTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void blankDisambiguationFallbackLeavesQueryRewriteSuperTokenTrace() {
        LlmClient blankLlm = prompt -> "";
        DomainTermDictionary emptyDictionary = query -> Set.of();
        QueryDisambiguationService service = new QueryDisambiguationService(
                blankLlm,
                new ObjectMapper(),
                emptyDictionary,
                new DisambiguationPromptBuilder(),
                new NoiseClipper());

        DisambiguationResult result = service.clarify(
                "GraphRAG ops console debug smoke query rewrite",
                List.of());

        assertNotNull(result);
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.superTokens.enabled"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.branchCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.tokenCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.subModelCount"));
        assertEquals(List.of("definition", "alias", "relation"),
                TraceStore.get("queryTransformer.subQueries.superTokens.axes"));
        assertEquals("disambiguation-blank-fallback",
                TraceStore.get("queryTransformer.subQueries.fallback.reason"));
    }
}
