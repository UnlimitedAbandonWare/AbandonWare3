package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.example.lms.cfvm.CfvmFailureRecorder;
import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.budget.RetrievalBudgetGovernor;
import com.example.lms.service.rag.budget.RetrievalBudgetProperties;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtremeZBurstAspectTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void defaultOffReturnsBaseAndTracesDisabled() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(), props,
                contradictionProvider(null), debugProvider(null));
        List<Content> base = List.of(Content.from("base"));

        Object out = aspect.aroundHybridRetrieve(new FakePjp(base, query("dynamic rag")));

        assertSame(base, out);
        assertEquals("disabled", TraceStore.get("extremez.skipReason"));
        assertEquals("disabled", TraceStore.get("extremez.bypassReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.activated"));
    }

    @Test
    void planOverrideCanEnableButMissingRetrieverFailsSoft() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(), props,
                contradictionProvider(null), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        List<Content> base = List.of();

        Object out = aspect.aroundHybridRetrieve(new FakePjp(base, query("dynamic rag")));

        assertSame(base, out);
        assertEquals("web_retriever_missing", TraceStore.get("extremez.skipReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.activated"));
    }

    @Test
    void executionPlanPrimaryOverdriveSuppressesExtremeZEvenWhenFlagRemainsTrue() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(3);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("extra evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("executionPlan.primaryMode", "OVERDRIVE");
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        List<Content> base = List.of();

        Object out = aspect.aroundHybridRetrieve(new FakePjp(base, query("conflicting special modes")));

        assertSame(base, out);
        assertEquals("special_mode_overdrive", TraceStore.get("extremez.skipReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("specialMode.conflict.extremeZ.suppressed"));
    }

    @Test
    void canonicalBurstHandlerActivationSkipsAopExpansionForSameRequest() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(3);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("extra evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("extremez.execute.activated", true);
        List<Content> base = List.of();

        Object out = aspect.aroundHybridRetrieve(new FakePjp(base, query("avoid duplicate expansion")));

        assertSame(base, out);
        assertEquals(0, retriever.queries.size());
        assertEquals("burst_handler_already_ran", TraceStore.get("extremez.skipReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.activated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void variantsAreBoundedAndDoNotRepeatOriginalQuery() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("buildVariants", String.class, int.class);
        method.setAccessible(true);

        List<String> variants = (List<String>) method.invoke(aspect, "dynamic rag routing", 2);

        assertTrue(variants.size() <= 2);
        assertFalse(variants.contains("dynamic rag routing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void englishVariantsUseEnglishBoostersWithoutKoreanSuffixes() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("buildVariants", String.class, int.class);
        method.setAccessible(true);

        List<String> variants = (List<String>) method.invoke(
                aspect,
                "machine learning optimization benchmark",
                12);

        assertTrue(variants.stream().anyMatch(v -> v.endsWith(" guide") || v.endsWith(" official")), variants::toString);
        assertTrue(variants.stream().noneMatch(v ->
                v.endsWith(" 정리") || v.endsWith(" 공식") || v.endsWith(" 출처") || v.endsWith(" 최신")), variants::toString);
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.variants.langDetect.hasKorean"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.variants.langDetect.hasEnglishOnly"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void koreanVariantsKeepKoreanBoostersAndTraceLanguage() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("buildVariants", String.class, int.class);
        method.setAccessible(true);

        List<String> variants = (List<String>) method.invoke(aspect, "RAG 기반 검색 시스템", 12);

        assertTrue(variants.stream().anyMatch(v -> v.endsWith(" 정리") || v.endsWith(" 공식")), variants::toString);
        assertTrue(variants.stream().noneMatch(v -> v.endsWith(" guide") || v.endsWith(" official")), variants::toString);
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.variants.langDetect.hasKorean"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.variants.langDetect.hasEnglishOnly"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void planGachaVariantsArePrependedWithoutRawTraceLeak() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod(
                "buildVariants", String.class, int.class, GuardContext.class);
        method.setAccessible(true);
        GuardContext ctx = new GuardContext();
        String raw = "dynamic rag routing secret variant";
        ctx.putPlanOverride("extremeZ.gachaVariants", List.of(
                raw,
                "dynamic rag routing",
                "dynamic rag routing alternate authority lane"));

        List<String> variants = (List<String>) method.invoke(aspect, "dynamic rag routing", 2, ctx);

        assertEquals(2, variants.size());
        assertEquals(raw, variants.get(0));
        assertEquals("dynamic rag routing alternate authority lane", variants.get(1));
        assertFalse(variants.contains("dynamic rag routing"));
        assertEquals(2, TraceStore.get("extremez.gachaVariants.plan.count"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
    }

    @Test
    @SuppressWarnings("unchecked")
    void highContradictionCanActivateWhenBaseDocsAreNotSparse() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("extra evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.95d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);

        List<Content> base = List.of(
                Content.from("A says the KPI is 10"),
                Content.from("B says the KPI is 99")
        );

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("compare KPI claims")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("contradiction", TraceStore.get("extremez.activation.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.risk.trigger"));
        assertEquals(1, TraceStore.get("extremez.subQueryCount"));
        assertEquals(1, TraceStore.get("extremez.parallelBranchCount"));
        assertEquals(3, TraceStore.get("extremez.mergedDocCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.rrfApplied"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void highWebErrorRateCanActivateWhenBaseDocsAreNotSparse() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("error-rate rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.await.events.count", 4L);
        TraceStore.put("web.await.events.timeout.count", 3L);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("hard sparse web")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("error_rate", TraceStore.get("extremez.activation.reason"));
        assertEquals(0.75d, (Double) TraceStore.get("extremez.risk.errorRate"), 0.0001d);
    }

    @Test
    @SuppressWarnings("unchecked")
    void afterFilterStarvationCanActivateWhenBaseDocsAreNotSparse() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("after-filter rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.naver.filter.rawCount", 3L);
        TraceStore.put("web.naver.afterFilterCount", 0L);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("official source clamp")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("starvation", TraceStore.get("extremez.activation.reason"));
        assertEquals(1.0d, (Double) TraceStore.get("extremez.risk.starvationScore"), 0.0001d);
        assertEquals("after_filter_starvation", TraceStore.get("extremez.risk.primaryCause"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tavilyAfterFilterStarvationCanActivateWhenBaseDocsAreNotSparse() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("tavily after-filter rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.tavily.returnedCount", 3L);
        TraceStore.put("web.tavily.afterFilterCount", 0L);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("tavily source clamp")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("starvation", TraceStore.get("extremez.activation.reason"));
        assertEquals(1.0d, (Double) TraceStore.get("extremez.risk.starvationScore"), 0.0001d);
        assertEquals("after_filter_starvation", TraceStore.get("extremez.risk.primaryCause"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tavilyZeroResultsCanActivateAsWebStarvation() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("tavily zero-result rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.tavily.zeroResults", true);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("tavily empty result query")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("starvation", TraceStore.get("extremez.activation.reason"));
        assertEquals(0.65d, (Double) TraceStore.get("extremez.risk.starvationScore"), 0.0001d);
        assertEquals("web_starvation", TraceStore.get("extremez.risk.primaryCause"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerDisabledCanActivateAsRetrievalFailure() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("provider-disabled rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.brave.providerDisabled", true);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("provider disabled query")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("retrieval_failure", TraceStore.get("extremez.activation.reason"));
        assertEquals(1.0d, (Double) TraceStore.get("extremez.risk.retrievalFailureRate"), 0.0001d);
        assertEquals("provider_disabled", TraceStore.get("extremez.risk.primaryCause"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tavilyProviderDisabledCanActivateAsRetrievalFailure() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("tavily disabled rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.tavily.providerDisabled", true);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("tavily disabled query")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("retrieval_failure", TraceStore.get("extremez.activation.reason"));
        assertEquals(1.0d, (Double) TraceStore.get("extremez.risk.retrievalFailureRate"), 0.0001d);
        assertEquals("provider_disabled", TraceStore.get("extremez.risk.primaryCause"));
    }

    @Test
    void providerDisabledRecordsCfvmFailurePattern() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("provider-disabled rescue evidence")));
        Path memory = tempDir.resolve("failure-pattern-memory.jsonl");
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(new RawMatrixBuffer()),
                provider(new FailurePatternMemoryService(
                        new ObjectMapper(), new ToolManifestCatalog(), tempDir, memory)));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null),
                null, null, provider(recorder));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.naver.providerDisabled", true);

        aspect.aroundHybridRetrieve(new FakePjp(
                List.of(Content.from("stable a"), Content.from("stable b")),
                query("provider disabled query")));

        String line = Files.readString(memory);
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.recorder.available"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.recorder.buffered"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.recorder.memory.recorded"));
        assertTrue(line.contains("cfvm_failure_pattern"));
        assertFalse(line.contains("provider disabled query"));
    }

    @Test
    void ablationCauseIsRecordedWithoutRawQuery() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("ablation rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("orch.debug.ablation.strike",
                List.of(Map.of("factor", "qtx.llm.modelRequired", "deltaProb", 0.42d)));
        String rawQuery = "secret ablation query should not appear";

        aspect.aroundHybridRetrieve(new FakePjp(
                List.of(Content.from("stable a"), Content.from("stable b")),
                query(rawQuery)));

        assertEquals("qtx.llm.modelRequired", TraceStore.get("extremez.risk.primaryCause"));
        assertFalse(String.valueOf(TraceStore.get("extremez.risk.patternId")).isBlank());
        assertFalse(TraceStore.getAll().containsValue(rawQuery));
    }

    @Test
    void riskTraceDoesNotExposeRawQueryText() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.95d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        String rawQuery = "secret raw query should not appear";

        aspect.aroundHybridRetrieve(new FakePjp(
                List.of(Content.from("claim one"), Content.from("claim two")),
                query(rawQuery)));

        assertFalse(TraceStore.getAll().containsValue(rawQuery));
        assertEquals(SafeRedactor.hash12(rawQuery), TraceStore.get("extremez.query.hash"));
    }

    @Test
    void riskEventDisabledReasonUsesTraceLabels() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"));

        assertFalse(source.contains("data.put(\"disabledReason\", activated ? \"\" : risk.reason());"));
        assertFalse(source.contains(
                "data.put(\"disabledReason\", activated ? \"\" : SafeRedactor.safeMessage(risk.reason(), 120));"));
        assertFalse(source.contains("\"extremez.risk.\" + risk.reason()"));
        assertTrue(source.contains(
                "String safeReason = SafeRedactor.traceLabelOrFallback(risk.reason(), \"unknown\");"));
        assertTrue(source.contains("data.put(\"disabledReason\", activated ? \"\" : safeReason);"));
        assertTrue(source.contains("String eventFingerprint = \"extremez.risk.\" + safeReason;"));
    }

    @Test
    void noExceptionIgnoreBlocksRemainInExtremeZAspect() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"));

        assertFalse(source.contains("catch (Exception ignore)"));
    }

    @Test
    void internalFailSoftFallbacksLeaveBreadcrumbs() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"));

        assertTrue(source.contains("traceFailure(\"fingerprint.text\", e);"));
        assertTrue(source.contains("traceFailure(\"contradiction.score\", e);"));
        assertTrue(source.contains("traceFailure(\"traceFailureSignals.trace\", ignore);"));
        assertTrue(source.contains("traceFailure(\"traceFailureSignals.patternId\", ignore);"));
        assertTrue(source.contains("traceFailure(\"blackbox.refresh\", ignore);"));
        assertTrue(source.contains("traceFailure(\"riskEvent.emit\", ignore);"));
        assertTrue(source.contains("traceFailure(\"cfvm.recorder.provider\", ex);"));
        assertTrue(source.contains("log.debug(\"[ExtremeZ] trace skipped key={} err={}"));
        assertTrue(source.contains("private static void traceFailure(String stage, Throwable e)"));
    }

    @Test
    void numericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"));

        assertParserCatchNarrowed(source, "private static long toLong");
        assertParserCatchNarrowed(source, "private static double toDouble");
        assertParserCatchNarrowed(source, "private double planDouble");
    }

    @Test
    void numericTraceFailuresUseStableInvalidNumberLabel() throws Exception {
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("traceFailure", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, "parse.double", new NumberFormatException("bad double"));
        method.invoke(null, "plan.double", new NumberFormatException("bad plan override"));
        method.invoke(null, "fingerprint.text", new IllegalStateException("boom"));

        assertEquals("invalid_number", TraceStore.get("extremez.parse.double.error"));
        assertEquals("invalid_number", TraceStore.get("extremez.plan.double.error"));
        assertEquals("IllegalStateException", TraceStore.get("extremez.fingerprint.text.error"));
    }

    @Test
    void numericParsersDropNonFiniteNumberValues() throws Exception {
        Method toLong = ExtremeZBurstAspect.class.getDeclaredMethod("toLong", Object.class);
        Method toDouble = ExtremeZBurstAspect.class.getDeclaredMethod("toDouble", Object.class);
        toLong.setAccessible(true);
        toDouble.setAccessible(true);

        assertEquals(0L, toLong.invoke(null, Double.POSITIVE_INFINITY));
        assertEquals("invalid_number", TraceStore.get("extremez.parse.long.error"));

        TraceStore.clear();

        assertEquals(0.0d, (Double) toDouble.invoke(null, Double.NaN), 0.0001d);
        assertEquals("invalid_number", TraceStore.get("extremez.parse.double.error"));
    }

    @Test
    void traceHelperUsesTraceLabelsForReasonStringScalars() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("trace", String.class, Object.class);
        method.setAccessible(true);
        String rawReason = "private risk reason with student detail";

        method.invoke(aspect, "extremez.risk.reason", rawReason);
        method.invoke(aspect, "extremez.base.count", 3);

        Object storedReason = TraceStore.get("extremez.risk.reason");
        assertTrue(String.valueOf(storedReason).startsWith("hash:"), String.valueOf(storedReason));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawReason));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("student detail"));
        assertEquals(3, TraceStore.get("extremez.base.count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void budgetGovernorDoesNotReusePriorTextureHitMetric() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null),
                null, provider(new RetrievalBudgetGovernor(new RetrievalBudgetProperties())));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("governVariants", String.class, List.class, int.class);
        method.setAccessible(true);
        TraceStore.put("rag.metrics.textureHitRate", 0.95d);

        List<String> out = (List<String>) method.invoke(aspect, "latest GraphRAG status", List.of("latest GraphRAG status"), 6);

        assertFalse(out.isEmpty());
        assertEquals("texture_unavailable", TraceStore.get("extremez.budget.textureReason"));
        assertEquals(0.0d, (Double) TraceStore.get("rag.metrics.textureHitRate"), 0.0001d);
    }

    private static Query query(String text) {
        return QueryUtils.buildQuery(text, Collections.emptyMap());
    }

    private static ObjectProvider<AnalyzeWebSearchRetriever> nullProvider() {
        return provider(null);
    }

    private static ObjectProvider<ContradictionScorer> contradictionProvider(ContradictionScorer scorer) {
        return provider(scorer);
    }

    private static ObjectProvider<DebugEventStore> debugProvider(DebugEventStore store) {
        return provider(store);
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse > start, "parser call should be locatable: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be locatable: " + signature);
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
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
                return value == null ? Collections.emptyIterator() : List.of(value).iterator();
            }
        };
    }

    private static final class FixedAnalyzeRetriever extends AnalyzeWebSearchRetriever {
        private final List<Content> results;
        private final List<String> queries = new ArrayList<>();

        private FixedAnalyzeRetriever(List<Content> results) {
            super(null, null, null, null, null, null);
            this.results = results == null ? List.of() : results;
        }

        @Override
        public List<Content> retrieve(Query query) {
            queries.add(query == null ? "" : query.text());
            return results;
        }
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

    private static final class FakePjp implements ProceedingJoinPoint {
        private final Object result;
        private final Object[] args;

        private FakePjp(Object result, Object... args) {
            this.result = result;
            this.args = args;
        }

        @Override
        public Object proceed() {
            return result;
        }

        @Override
        public Object proceed(Object[] args) {
            return result;
        }

        @Override
        public void set$AroundClosure(AroundClosure arc) {
        }

        @Override
        public Object getThis() {
            return this;
        }

        @Override
        public Object getTarget() {
            return this;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public Signature getSignature() {
            return null;
        }

        @Override
        public SourceLocation getSourceLocation() {
            return null;
        }

        @Override
        public String getKind() {
            return "method-execution";
        }

        @Override
        public JoinPoint.StaticPart getStaticPart() {
            return null;
        }

        @Override
        public String toShortString() {
            return "FakePjp";
        }

        @Override
        public String toLongString() {
            return "FakePjp";
        }
    }
}
