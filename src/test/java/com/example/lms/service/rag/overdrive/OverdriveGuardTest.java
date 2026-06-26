package com.example.lms.service.rag.overdrive;

import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.energy.ContradictionScorer;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverdriveGuardTest {

    private OverdriveGuard guard;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        GuardContextHolder.clear();
        guard = new OverdriveGuard(authority(), new FixedContradictionScorer(0.0d));
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "minPool", 4);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.55d);
        ReflectionTestUtils.setField(guard, "contradictionTh", 0.60d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.55d);
        ReflectionTestUtils.setField(guard, "errorRateTh", 0.35d);
        ReflectionTestUtils.setField(guard, "errorWeight", 0.10d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);
    }

    @AfterEach
    void tearDown() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void activatesOnSparsePoolAndTracesDecision() {
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.50d);

        boolean activated = guard.shouldActivate("hard sparse question", List.of(Content.from("only one")));

        assertTrue(activated);
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.activated"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.triggered"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.trigger.activated"));
        assertEquals((Double) TraceStore.get("overdrive.score"),
                (Double) TraceStore.get("overdrive.trigger.score"), 0.0001d);
        assertEquals("base_threshold", TraceStore.get("overdrive.reason"));
        assertEquals(1, TraceStore.get("overdrive.candidates.count"));
        assertEquals(1, TraceStore.get("overdrive.candidateCount"));
        assertEquals(0.25d, (Double) TraceStore.get("overdrive.authorityMean"), 0.0001d);
        assertTrue(String.valueOf(TraceStore.get("overdrive.triggerReasons")).contains("sparse"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.trigger.sparse"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.trigger.lowAuth"));
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.trigger.contradicted"));
        assertEquals(1, TraceStore.get("overdrive.trigger.candidateCount"));
        assertEquals(0.25d, (Double) TraceStore.get("overdrive.trigger.avgAuthority"), 0.0001d);
        assertEquals(0.0d, (Double) TraceStore.get("overdrive.trigger.contradictionScore"), 0.0001d);
        assertEquals(0, TraceStore.get("overdrive.stagesApplied"));
        assertEquals(-1, TraceStore.get("overdrive.finalCandidateCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.exactPhraseProbeUsed"));
    }

    @Test
    void skipsWhenDisabled() {
        ReflectionTestUtils.setField(guard, "enabled", false);

        assertFalse(guard.shouldActivate("query", List.of(Content.from("x"))));
        assertEquals("disabled", TraceStore.get("overdrive.skipReason"));
        assertEquals("disabled", TraceStore.get("overdrive.bypassReason"));
    }

    @Test
    void guardContextCanDisableOverdriveBeforeContradictionWork() {
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("overdrive.enabled", false);
        GuardContextHolder.set(ctx);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence"),
                Content.from("URL: https://www.gov.kr/c\nstable evidence"),
                Content.from("URL: https://www.gov.kr/d\nstable evidence")
        );

        assertFalse(guard.shouldActivate("official query", docs));
        assertEquals("guard_context_skip", TraceStore.get("overdrive.skipReason"));
    }

    @Test
    void activatesOnContradictionWhenBaseSignalsAreWeak() {
        guard = new OverdriveGuard(authority(), new FixedContradictionScorer(0.95d));
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.55d);
        ReflectionTestUtils.setField(guard, "contradictionTh", 0.60d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.30d);
        ReflectionTestUtils.setField(guard, "errorRateTh", 0.35d);
        ReflectionTestUtils.setField(guard, "errorWeight", 0.10d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);

        List<Content> docs = List.of(
                Content.from("A says revenue is 10"),
                Content.from("B says revenue is 99")
        );

        assertTrue(guard.shouldActivate("compare facts", docs));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.activated"));
        assertEquals("threshold", TraceStore.get("overdrive.reason"));
    }

    @Test
    void recordsErrorRateAndCanUseItAsBoundedSignal() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.05d);
        ReflectionTestUtils.setField(guard, "errorRateTh", 0.35d);
        ReflectionTestUtils.setField(guard, "errorWeight", 0.10d);
        TraceStore.put("web.await.events.count", 4L);
        TraceStore.put("web.await.events.timeout.count", 3L);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertTrue(guard.shouldActivate("web unstable query", docs));
        assertEquals(0.75d, (Double) TraceStore.get("overdrive.error.rate"), 0.0001d);
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.activated"));
    }

    @Test
    void recordsStarvationAsBoundedScoreOnly() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.20d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);
        TraceStore.put("web.naver.filter.rawCount", 5L);
        TraceStore.put("web.naver.afterFilterCount", 0L);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertFalse(guard.shouldActivate("secret starvation query should not leak", docs));
        assertEquals(0.16d, (Double) TraceStore.get("overdrive.score"), 0.0001d);
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.activated"));
        assertFalse(TraceStore.getAll().containsValue("secret starvation query should not leak"));
    }

    @Test
    void recordsTavilyStarvationAsBoundedScoreOnly() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.20d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);
        TraceStore.put("web.tavily.returnedCount", 5L);
        TraceStore.put("web.tavily.afterFilterCount", 0L);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertFalse(guard.shouldActivate("secret tavily starvation query should not leak", docs));
        assertEquals(0.16d, (Double) TraceStore.get("overdrive.score"), 0.0001d);
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.activated"));
        assertFalse(TraceStore.getAll().containsValue("secret tavily starvation query should not leak"));
    }

    @Test
    void tavilyProviderDisabledAndZeroResultContributeToRetrievalFailure() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.05d);
        ReflectionTestUtils.setField(guard, "errorRateTh", 0.35d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.zeroResults", true);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertTrue(guard.shouldActivate("secret tavily disabled query should not leak", docs));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.activated"));
        assertFalse(TraceStore.getAll().containsValue("secret tavily disabled query should not leak"));
    }

    @Test
    void planAggressiveLowersThresholdForStarvationRouting() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.20d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("overdrive.aggressive", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.naver.filter.rawCount", 5L);
        TraceStore.put("web.naver.afterFilterCount", 0L);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertTrue(guard.shouldActivate("secret starvation query should not leak", docs));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.aggressive"));
        assertEquals(0.12d, (Double) TraceStore.get("overdrive.score.threshold.effective"), 0.0001d);
        assertFalse(TraceStore.getAll().containsValue("secret starvation query should not leak"));
    }

    @Test
    void blackboxRiskContributesAsBoundedRetrievalFailureSignal() {
        guard = new OverdriveGuard(authority(), new FixedContradictionScorer(0.0d), provider(blackboxService(true)));
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.10d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.20d);
        TraceStore.put("ablation.events.count", 2);
        TraceStore.put("ablation.probabilities", List.of(
                java.util.Map.of("step", "web.await", "guard", "missing_future", "p", 0.90d, "delta", 0.10d)));

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertTrue(guard.shouldActivate("raw blackbox query should not leak", docs));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.activated"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.blackbox.available"));
        assertEquals(0.20d, (Double) TraceStore.get("overdrive.score"), 0.0001d);
        assertEquals("web_await_bypass", TraceStore.get("overdrive.blackbox.restoreAction"));
        assertFalse(TraceStore.getAll().containsValue("raw blackbox query should not leak"));
    }

    @Test
    void disabledBlackboxConsumerDoesNotWriteBlackboxRiskTrace() {
        guard = new OverdriveGuard(authority(), new FixedContradictionScorer(0.0d), provider(blackboxService(false)));
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.90d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.20d);
        TraceStore.put("web.naver.providerDisabled", true);

        assertFalse(guard.shouldActivate("provider disabled but blackbox off", List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence"))));

        assertFalse(TraceStore.getAll().containsKey("blackbox.risk.riskScore"));
        assertFalse(TraceStore.getAll().containsKey("blackbox.risk.dominantFailure"));
    }

    @Test
    void missingBlackboxProviderLeavesExplicitAbsentBreadcrumb() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.90d);

        assertFalse(guard.shouldActivate("blackbox absent query", List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence"))));

        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.blackbox.absent"));
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.blackbox.available"));
        assertEquals("provider_unavailable", TraceStore.get("overdrive.blackbox.disabledReason"));
        assertFalse(TraceStore.getAll().containsValue("blackbox absent query"));
    }

    @Test
    void blackboxRefreshFailureLeavesBreadcrumb() {
        guard = new OverdriveGuard(authority(), new FixedContradictionScorer(0.0d), throwingProvider());
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.90d);

        assertFalse(guard.shouldActivate("blackbox failure query", List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence"))));

        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.blackbox.refresh.failed"));
        assertEquals("IllegalStateException", TraceStore.get("overdrive.blackbox.refresh.errorType"));
        assertFalse(TraceStore.getAll().containsValue("blackbox failure query"));
    }

    @Test
    void overdriveReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"));

        assertFalse(source.contains("TraceStore.put(\"overdrive.skipReason\", activated ? \"\" : reason);"));
        assertFalse(source.contains("TraceStore.put(\"overdrive.reason\", reason);"));
        assertFalse(source.contains("String safeReason = SafeRedactor.safeMessage(reason, 120);"));
        assertTrue(source.contains("String safeReason = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
        assertTrue(source.contains("TraceStore.put(\"overdrive.skipReason\", activated ? \"\" : safeReason);"));
        assertTrue(source.contains("TraceStore.put(\"overdrive.reason\", safeReason);"));
    }

    @Test
    void traceDecisionFailureIsDiagnosableInsteadOfSilentIgnore() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"));
        String body = source.substring(source.indexOf("private static void traceDecision"),
                source.indexOf("private static String safeHash"));

        assertFalse(body.contains("catch (Throwable ignore)"));
        assertTrue(body.contains("log.debug(\"[OverdriveGuard] traceDecision failed err={}\""));
    }

    @Test
    void autowiredAuthorityScorerUsesCanonicalQualifier() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"));

        assertTrue(source.contains("@Qualifier(\"authAuthorityScorer\")"));
        assertTrue(source.indexOf("@Qualifier(\"authAuthorityScorer\")")
                < source.indexOf("AuthorityScorer authority,"));
    }

    @Test
    void overdriveSettingsAreConfigurationPropertiesBackedInsteadOfInlineValues() throws Exception {
        String guardSource = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"));
        String propsSource = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/overdrive/OverdriveProperties.java"));

        assertFalse(guardSource.contains("@Value(\"${rag.overdrive."),
                "OverdriveGuard should not own raw @Value bindings");
        assertTrue(guardSource.contains("OverdriveProperties properties"),
                "OverdriveGuard should receive the typed overdrive settings object");
        assertTrue(propsSource.contains("@ConfigurationProperties(prefix = \"rag.overdrive\")"));
        assertTrue(propsSource.contains("private Trigger trigger = new Trigger();"));
    }

    @Test
    void traceSnapshotFailureLeavesStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"));

        assertTrue(source.contains("stage=trace.snapshot"));
        assertTrue(source.contains("stage=blackbox.refresh"));
        assertTrue(source.contains("stage=long.parse"));
        assertTrue(source.contains("stage=double.parse"));
        assertTrue(source.contains("stage=hash.fallback"));
        assertTrue(source.contains("err=trace-failure"));
    }

    private static RagFailureBlackboxService blackboxService(boolean enabled) {
        RagFailureBlackboxService service = new RagFailureBlackboxService(null, null, null);
        ReflectionTestUtils.setField(service, "enabled", enabled);
        return service;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }

    private static ObjectProvider<RagFailureBlackboxService> throwingProvider() {
        return new ObjectProvider<>() {
            @Override
            public RagFailureBlackboxService getObject(Object... args) {
                throw new IllegalStateException("ownerToken=secret-blackbox");
            }

            @Override
            public RagFailureBlackboxService getObject() {
                throw new IllegalStateException("ownerToken=secret-blackbox");
            }

            @Override
            public RagFailureBlackboxService getIfAvailable() {
                throw new IllegalStateException("ownerToken=secret-blackbox");
            }

            @Override
            public RagFailureBlackboxService getIfUnique() {
                throw new IllegalStateException("ownerToken=secret-blackbox");
            }
        };
    }

    private static AuthorityScorer authority() {
        return new AuthorityScorer("", "", "", "", "", "", "", "", "",
                1.0d, 0.85d, 0.80d, 0.70d, 0.55d, 0.25d);
    }

    private static final class FixedContradictionScorer extends ContradictionScorer {
        private final double score;

        private FixedContradictionScorer(double score) {
            this.score = score;
        }

        @Override
        public double score(String a, String b) {
            return score;
        }
    }
}
