package com.example.lms.service.rag;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.CitationGate;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.guard.EvidenceGate;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvidenceAttributionServiceTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        GuardContextHolder.clear();
        MDC.clear();
    }

    @Test
    void promotesOnlyGatePassedMetadataWithoutRawSnippets() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        Content web = Content.from(TextSegment.from(
                "alpha citation body that must not be copied into public metadata",
                Metadata.from(Map.of(
                        "title", "Official Alpha",
                        "url", "https://example.com/alpha",
                        "lineStart", 12,
                        "score", 0.87d))));
        Document local = Document.from(
                "local alpha document body",
                new Metadata(Map.of(
                        "title", "Alpha Manual",
                        "filePath", "docs/alpha.md",
                        "lineStart", 3,
                        "lineEnd", 7,
                        "rerankConfidence", 0.71d)));

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "alpha",
                List.of(web),
                List.of(),
                List.of(local),
                QueryDomain.GENERAL,
                false);

        assertEquals(2, promoted.size());
        assertEquals("W1", promoted.get(0).marker());
        assertEquals("https://example.com/alpha", promoted.get(0).source());
        assertEquals(12, promoted.get(0).lineStart());
        assertNull(promoted.get(0).lineEnd());
        assertEquals("D1", promoted.get(1).marker());
        assertEquals("docs/alpha.md", promoted.get(1).filePath());

        String publicDump = String.valueOf(TraceStore.get("rag.evidence.public"));
        assertTrue(publicDump.contains("Official Alpha"));
        assertFalse(publicDump.contains("must not be copied"));

        String answer = service.appendFinalEvidenceAppendix("Answer uses [W1].", promoted);
        assertTrue(answer.contains("### Sources"));
        assertTrue(answer.contains("[W1]"));
        assertTrue(answer.contains("confidence 0.870"));

        String fileAnswer = service.appendFinalEvidenceAppendix("Answer uses [D1].", promoted);
        assertFalse(fileAnswer.contains("docs/alpha.md"));
        assertTrue(fileAnswer.contains("pathHash=" + SafeRedactor.hashValue("docs/alpha.md")));
    }

    @Test
    void blockedGateProducesNoPublicEvidenceAndRecordsReason() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(false);
        Content web = Content.from(TextSegment.from(
                "alpha body",
                Metadata.from(Map.of("title", "Alpha", "url", "https://example.com/a"))));

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "alpha",
                List.of(web),
                List.of(),
                List.of(),
                QueryDomain.GENERAL,
                false);

        assertTrue(promoted.isEmpty());
        assertEquals("evidence_gate_blocked", TraceStore.get("rag.evidence.promotion.disabledReason"));
        assertEquals(List.of(), TraceStore.get("rag.evidence.public"));
    }

    @Test
    void gatePassedBatchStillPromotesOnlyCandidatesWithCitableLocator() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        Content titleOnly = Content.from(TextSegment.from(
                "alpha body with title only",
                Metadata.from(Map.of("title", "Title Only Alpha"))));
        Content citable = Content.from(TextSegment.from(
                "alpha body with public URL",
                Metadata.from(Map.of(
                        "title", "Citable Alpha",
                        "url", "https://example.com/alpha?ownerToken=secret#frag"))));

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "alpha",
                List.of(titleOnly, citable),
                List.of(),
                List.of(),
                QueryDomain.GENERAL,
                false);

        assertEquals(1, promoted.size());
        assertEquals("W2", promoted.get(0).marker());
        assertEquals("https://example.com/alpha", promoted.get(0).source());
        assertEquals(2, TraceStore.get("rag.evidence.promotion.candidateCount"));
        assertEquals(1, TraceStore.get("rag.evidence.promotion.citableLocatorCount"));
        String publicDump = String.valueOf(TraceStore.get("rag.evidence.public"));
        assertTrue(publicDump.contains("Citable Alpha"));
        assertFalse(publicDump.contains("Title Only Alpha"));
        assertFalse(publicDump.contains("ownerToken"));
    }

    @Test
    void markerMismatchDoesNotAppendFallbackEvidence() {
        RagEvidenceAttributionService service = newService(true);
        RagEvidenceMetadata evidence = new RagEvidenceMetadata(
                "W2",
                "WEB",
                "Citable Alpha",
                "https://example.com/alpha",
                null,
                null,
                null,
                2,
                null,
                "unavailable");

        String answer = "Answer cites [W1].";
        String withAppendix = service.appendFinalEvidenceAppendix(answer, List.of(evidence));

        assertEquals(answer, withAppendix);
        assertEquals(true, TraceStore.get("rag.evidence.appendix.markerMismatch"));
        assertEquals(1, TraceStore.get("rag.evidence.appendix.requestedMarkerCount"));
        assertEquals(0, TraceStore.get("rag.evidence.appendix.matchedMarkerCount"));
    }

    @Test
    void breadcrumbsStoreCorrelationAsHashOnly() {
        MDC.put("x-request-id", "raw-evidence-request");
        MDC.put("sessionId", "raw-evidence-session");
        TraceStore.put("trace.id", "raw-evidence-trace");
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        Content web = Content.from(TextSegment.from(
                "alpha body",
                Metadata.from(Map.of("title", "Alpha", "url", "https://example.com/a"))));

        service.promoteForPrompt("alpha", List.of(web), List.of(), List.of(), QueryDomain.GENERAL, false);

        Object events = TraceStore.get("ml.breadcrumbs.v1");
        assertTrue(events instanceof List<?>);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) events).get(0);
        assertEquals(SafeRedactor.hashValue("raw-evidence-request"), row.get("requestId"));
        assertEquals(SafeRedactor.hashValue("raw-evidence-session"), row.get("sessionId"));
        assertTrue(row.get("data") instanceof Map<?, ?>);
        Map<?, ?> data = (Map<?, ?>) row.get("data");
        assertEquals(Boolean.TRUE, data.get("queryRedacted"));
        assertEquals(Boolean.TRUE, TraceStore.get("cihRag.breadcrumb.queryRedacted"));
        assertEquals(SafeRedactor.hashValue("raw-evidence-trace"), TraceStore.get("rag.control.last.breadcrumbId"));
        assertFalse(row.toString().contains("raw-evidence-request"));
        assertFalse(row.toString().contains("raw-evidence-session"));
        assertFalse(String.valueOf(TraceStore.get("rag.control.last.breadcrumbId")).contains("raw-evidence-trace"));
        assertFalse(String.valueOf(TraceStore.get("orch.events.v1")).contains("raw-evidence-trace"));
    }

    @Test
    void promotionDisabledReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java"));

        assertFalse(source.contains("TraceStore.put(\"rag.evidence.promotion.disabledReason\", reason);"));
        assertFalse(source.contains(
                "TraceStore.put(\"rag.evidence.promotion.disabledReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"rag.evidence.promotion.disabledReason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }

    @Test
    void ragEvidenceAttributionServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "evidence attribution fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void numericEvidenceMetadataHelpersDropNonFiniteNumbers() throws Exception {
        Method toInt = RagEvidenceAttributionService.class.getDeclaredMethod("toInt", Object.class);
        Method toDouble = RagEvidenceAttributionService.class.getDeclaredMethod("toDouble", Object.class);
        toInt.setAccessible(true);
        toDouble.setAccessible(true);

        assertNull(toInt.invoke(null, Double.POSITIVE_INFINITY));
        assertNull(toInt.invoke(null, Double.NaN));
        assertNull(toDouble.invoke(null, Double.NEGATIVE_INFINITY));
        assertNull(toDouble.invoke(null, "Infinity"));
    }

    private static RagEvidenceAttributionService newService(boolean evidenceAllowed) {
        return new RagEvidenceAttributionService(
                new StubEvidenceGate(evidenceAllowed),
                new CitationGate(),
                null);
    }

    private static final class StubEvidenceGate extends EvidenceGate {
        private final boolean allowed;

        StubEvidenceGate(boolean allowed) {
            super(0.0d, 0.0d, 0.0d, 0.0d);
            this.allowed = allowed;
        }

        @Override
        public boolean hasSufficientCoverage(
                String question,
                List<String> ragLines,
                List<String> memoryLines,
                List<String> kbLines,
                boolean isFollowUp,
                QueryDomain domain) {
            return allowed;
        }
    }
}
