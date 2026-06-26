package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAwareGuardScorecardTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void scorecardBlockRecommendationIsEnforcedByGuardDecision() {
        TraceStore.put("blackbox.risk.routingDecision", "BLOCK");
        TraceStore.put("blackbox.risk.blockRecommended", Boolean.TRUE);
        TraceStore.put("blackbox.risk.reasonCode", "block_policyrisk_observe_only");

        EvidenceAwareGuard guard = new EvidenceAwareGuard();
        List<EvidenceAwareGuard.EvidenceDoc> evidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context for a normal guard pass."));

        EvidenceAwareGuard.GuardDecision decision = guard.guardWithEvidence(
                "This is a normal grounded draft based on the source snippet.",
                evidence,
                0);

        assertEquals(EvidenceAwareGuard.GuardAction.BLOCK, decision.action());
        assertEquals("CONSTITUTIONAL_SCORECARD_BLOCK", TraceStore.get("guard.final.action"));
        assertEquals("block_policyrisk_observe_only", TraceStore.get("guard.final.action.reason"));
    }

    @Test
    void scorecardBlockReasonDoesNotExposeRawFreeFormReasonInMessage() {
        String fakeKey = "sk-" + "scorecardReasonSecret1234567890";
        String rawReason = "private prompt ownerToken=" + fakeKey + " needs manual review";
        TraceStore.put("blackbox.risk.routingDecision", "BLOCK");
        TraceStore.put("blackbox.risk.blockRecommended", Boolean.TRUE);
        TraceStore.put("blackbox.risk.reasonCode", rawReason);

        EvidenceAwareGuard guard = new EvidenceAwareGuard();
        List<EvidenceAwareGuard.EvidenceDoc> evidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context for a normal guard pass."));

        EvidenceAwareGuard.GuardDecision decision = guard.guardWithEvidence(
                "This is a normal grounded draft based on the source snippet.",
                evidence,
                0);

        String message = decision.finalDraft();
        String traceReason = String.valueOf(TraceStore.get("guard.final.action.reason"));
        assertEquals(EvidenceAwareGuard.GuardAction.BLOCK, decision.action());
        assertTrue(traceReason.startsWith("hash:"), traceReason);
        assertTrue(message.contains("reason=hash:"), message);
        assertFalse(message.contains("private prompt"), message);
        assertFalse(message.contains("ownerToken"), message);
        assertFalse(message.contains(fakeKey), message);
        assertFalse(traceReason.contains("private prompt"), traceReason);
        assertFalse(traceReason.contains(fakeKey), traceReason);
    }

    @Test
    void degradedEvidenceDiagnosticsUseLastStageCountsFallback() {
        java.util.Map<String, Object> stageCounts = new java.util.LinkedHashMap<>();
        stageCounts.put("NOFILTER_SAFE", 2);
        stageCounts.put("OFFICIAL", 0);
        TraceStore.put("stageCountsSelectedFromOut.last", stageCounts);

        String output = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context.")));

        assertTrue(output.contains("- web.failsoft.stageCountsSelectedFromOut: {NOFILTER_SAFE=2, OFFICIAL=0}"),
                output);
        assertFalse(output.contains("- web.failsoft.stageCountsSelectedFromOut: (missing)"), output);
    }

    @Test
    void degradedEvidenceDiagnosticsUseCanonicalStarvationTriggerFallback() {
        TraceStore.put("starvationFallback.trigger", "officialOnly->NOFILTER_SAFE");

        String output = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context.")));

        assertTrue(output.contains("- web.failsoft.starvationFallback.trigger: officialOnly->NOFILTER_SAFE"),
                output);
        assertTrue(output.contains("web.failsoft.starvationFallback(trigger=officialOnly->NOFILTER_SAFE"),
                output);
        assertFalse(output.contains("- web.failsoft.starvationFallback.trigger: (missing)"), output);
    }

    @Test
    void degradedEvidenceDiagnosticsUseCanonicalPoolAndNamespacedCacheFallbacks() {
        TraceStore.put("starvationFallback.poolUsed", "cache_only");
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", 3);

        String output = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context.")));

        assertTrue(output.contains("- web.failsoft.starvationFallback.poolUsed: cache_only"), output);
        assertTrue(output.contains("- cacheOnly.merged.count: 3"), output);
        assertFalse(output.contains("- web.failsoft.starvationFallback.poolUsed: (missing)"), output);
        assertFalse(output.contains("- cacheOnly.merged.count: (missing)"), output);
    }

    @Test
    void minCitationDetourUsesCanonicalStarvationTriggerForEntityEscalation() {
        GuardContext ctx = new GuardContext();
        ctx.setMinCitations(2);
        ctx.setEntityQuery(true);
        ctx.setUserQuery("Who is Ada Lovelace?");
        GuardContextHolder.set(ctx);
        TraceStore.put("starvationFallback.trigger", "BELOW_MIN_CITATIONS");

        EvidenceAwareGuard.GuardDecision decision = new EvidenceAwareGuard().guardWithEvidence(
                "Ada Lovelace was a computing pioneer.",
                List.of(new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context.")),
                0);

        assertEquals(EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY, decision.action());
        assertEquals("ESCALATE_REWRITE", TraceStore.get("guard.detour.route"));
        assertEquals(Boolean.TRUE, TraceStore.get("guard.detour.forceEscalate"));
        assertEquals("BELOW_MIN_CITATIONS", TraceStore.get("guard.detour.forceEscalate.trigger"));
    }

    @Test
    void escalationFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[guard] escalation failed, falling back to original draft. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
    }

    @Test
    void evidenceListUrlEnrichmentLivesOutsideGuardLargeFile() throws Exception {
        Path guardPath = Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java");
        Path helperPath = Path.of("main/java/com/example/lms/service/guard/EvidenceDocListEnricher.java");

        String guard = Files.readString(guardPath, StandardCharsets.UTF_8);

        assertTrue(Files.exists(helperPath), "URL-derived evidence list enrichment should live outside the guard large file");
        String helper = Files.readString(helperPath, StandardCharsets.UTF_8);
        assertTrue(guard.contains("EvidenceDocListEnricher.enrich(docs)"));
        assertFalse(guard.contains("private static java.util.List<EvidenceDoc> enrichEvidenceDocsForList("));
        assertFalse(guard.contains("private static String urlHost("));
        assertFalse(guard.contains("private static String deriveSnippetFromUrl("));
        assertTrue(helper.contains("final class EvidenceDocListEnricher"));
        assertTrue(helper.contains("static java.util.List<EvidenceAwareGuard.EvidenceDoc> enrich("));
    }

    @Test
    void evidenceAwareGuardDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java"),
                StandardCharsets.UTF_8);

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "EvidenceAwareGuard guard diagnostics must leave trace/debug breadcrumbs instead of exact empty catch blocks");
    }

    @Test
    void bestEffortFailureNumericTraceUsesStableErrorType() throws Exception {
        Method method = EvidenceAwareGuard.class.getDeclaredMethod(
                "traceBestEffortFailure", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, "guard.test.numeric", new NumberFormatException("raw private token"));

        assertEquals(Boolean.TRUE, TraceStore.get("guard.test.numeric.failed"));
        assertEquals("invalid_number", TraceStore.get("guard.test.numeric.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private token"));
    }
}
