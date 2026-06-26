package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EvidenceAnswerComposerTraceTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void composeLeavesRedactedPostprocessBreadcrumbs() {
        String rawQuery = "private evidence composer query ownerToken=raw-secret";
        List<EvidenceAwareGuard.EvidenceDoc> evidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc("doc-1", "Title One", "This snippet explains alpha. Another sentence.", "https://example.test/a"),
                new EvidenceAwareGuard.EvidenceDoc("doc-2", "Title Two", "Second snippet explains beta.", "https://example.test/b"),
                new EvidenceAwareGuard.EvidenceDoc("doc-3", "Title Three", "Third snippet explains gamma.", "https://example.test/c"),
                new EvidenceAwareGuard.EvidenceDoc("doc-4", "Title Four", "Fourth snippet explains delta.", "https://example.test/d"),
                new EvidenceAwareGuard.EvidenceDoc("doc-5", "Title Five", "Fifth snippet explains epsilon.", "https://example.test/e"),
                new EvidenceAwareGuard.EvidenceDoc("doc-6", "Title Six", "Sixth snippet should not be listed.", "https://example.test/f")
        );

        String answer = new EvidenceAnswerComposer().compose(rawQuery, evidence, false);

        assertFalse(answer.isBlank());
        assertEquals(Boolean.TRUE, TraceStore.get("evidenceAnswerComposer.composed"));
        assertEquals(6, TraceStore.get("evidenceAnswerComposer.evidenceCount"));
        assertEquals(5, TraceStore.get("evidenceAnswerComposer.usedEvidenceCount"));
        assertEquals(1, TraceStore.get("evidenceAnswerComposer.unusedEvidenceCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("evidenceAnswerComposer.explanationIncluded"));
        assertEquals(SafeRedactor.hash12(rawQuery), TraceStore.get("evidenceAnswerComposer.queryHash12"));
        assertEquals(rawQuery.length(), TraceStore.get("evidenceAnswerComposer.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=raw-secret"));
    }
}
