package com.example.lms.prompt;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardPromptBuilderEvidenceMetadataTest {

    private final StandardPromptBuilder builder = new StandardPromptBuilder();

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void rendersCitableEvidenceMetadataBeforeSearchResults() {
        Content web = Content.from(TextSegment.from(
                "alpha searchable body",
                Metadata.from(Map.of("title", "Alpha", "url", "https://example.com/a"))));
        PromptContext ctx = PromptContext.builder()
                .web(List.of(web))
                .evidence(List.of(new RagEvidenceMetadata(
                        "W1",
                        "WEB",
                        "Alpha",
                        "https://example.com/a",
                        null,
                        5,
                        null,
                        1,
                        0.91d,
                        "score")))
                .build();

        String prompt = builder.build(List.of(ctx), "alpha?");

        assertTrue(prompt.contains("### CITABLE EVIDENCE METADATA"), prompt);
        assertTrue(prompt.contains("Only markers in this block are public citations"), prompt);
        assertTrue(prompt.contains("[W1] kind=WEB"), prompt);
        assertTrue(prompt.contains("lines=5"), prompt);
        assertTrue(prompt.indexOf("### CITABLE EVIDENCE METADATA") < prompt.indexOf("### SEARCH RESULTS"), prompt);
        assertTrue(prompt.contains("[W1] alpha searchable body"), prompt);
        assertEquals(1, TraceStore.get("prompt.citableEvidenceRenderedCount"));
        assertEquals(false, TraceStore.get("promptBuilder.evidenceEmpty"));
    }

    @Test
    void emptyEvidenceIsNullSafe() {
        PromptContext ctx = PromptContext.builder().build();

        String prompt = builder.build(List.of(ctx), "no evidence");

        assertTrue(prompt.contains("### SEARCH RESULTS"), prompt);
        assertEquals(0, TraceStore.get("prompt.citableEvidenceRenderedCount"));
        assertEquals(true, TraceStore.get("promptBuilder.evidenceEmpty"));
    }
}
