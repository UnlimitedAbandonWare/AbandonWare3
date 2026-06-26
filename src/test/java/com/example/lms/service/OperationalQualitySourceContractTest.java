package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class OperationalQualitySourceContractTest {

    @Test
    void chatWorkflowReinforcementFailureLeavesRedactedTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private void reinforce(");
        int end = source.indexOf("private String decideFinalQuery", start);
        assertTrue(start >= 0 && end > start, "reinforce() method span should be locatable");
        String reinforce = source.substring(start, end);

        assertTrue(reinforce.contains("TraceStore.inc(\"memory.reinforce.failed\")"),
                "reinforce() must leave a TraceStore breadcrumb when memory reinforcement fails");
        assertTrue(reinforce.contains("SafeRedactor.hashValue(sessionKey)"),
                "reinforce() must hash the session key before logging");
        assertTrue(reinforce.contains("memory reinforcement failed sessionHash={}")
                && reinforce.contains("t.getClass().getSimpleName()"),
                "reinforce() should log only redacted session hash and exception class");
        assertFalse(reinforce.contains("catch (Throwable ignore)"),
                "reinforce() must not silently swallow Throwable");
    }

    @Test
    void naverSearchSemaphoreIsInstanceScopedAndSchedulerHasDestroyHook() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/NaverSearchService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("private static final Semaphore REQUEST_SEMAPHORE"),
                "Naver API semaphore must not be static global state");
        assertTrue(source.contains("private final Semaphore REQUEST_SEMAPHORE = new Semaphore(MAX_CONCURRENT_API, true)"),
                "Naver API semaphore should be fair and instance-scoped");
        assertTrue(source.contains("@PreDestroy") && source.contains("shutdownScheduler()"),
                "NaverSearchService should clean up its scheduler on Spring shutdown");
        assertTrue(source.contains("naverIoScheduler = null")
                && source.contains("s.dispose()")
                && source.contains("REQUEST_SEMAPHORE.drainPermits()"),
                "shutdownScheduler() should clear the scheduler reference, dispose it, and drain permits");
    }

    @Test
    void chatWorkflowIntentFallbackLeavesRedactedTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private String inferIntent(");
        int end = source.indexOf("private String detectRisk(", start);
        assertTrue(start >= 0 && end > start, "inferIntent() method span should be locatable");
        String inferIntent = source.substring(start, end);

        assertTrue(inferIntent.contains("TraceStore.putIfAbsent(\"queryTransformer.bypassed\", \"true\")"),
                "inferIntent() fallback should leave a queryTransformer bypass breadcrumb");
        assertTrue(inferIntent.contains("TraceStore.putIfAbsent(\"queryTransformer.reason\", \"infer_intent_failed\")"),
                "inferIntent() fallback should record a stable normalized reason");
        assertTrue(inferIntent.contains("[inferIntent] query transformer bypassed err={}")
                && inferIntent.contains("e.getClass().getSimpleName()"),
                "inferIntent() fallback should log only the exception class");
        assertFalse(inferIntent.contains("SafeRedactor.safeMessage(String.valueOf(q)"),
                "inferIntent() fallback must not log the raw query");
    }

    @Test
    void cacheOnlyProviderRescueErrorsUseStableOperationalLabels() throws Exception {
        String brave = Files.readString(
                Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"),
                StandardCharsets.UTF_8);
        String naver = Files.readString(
                Path.of("main/java/com/example/lms/service/NaverSearchService.java"),
                StandardCharsets.UTF_8);

        assertTrue(brave.contains("TraceStore.putIfAbsent(\"web.brave.cacheOnly.error\", \"cache_only_failed\")"),
                "Brave cache-only rescue errors should use a stable operational reason");
        assertFalse(brave.contains("TraceStore.putIfAbsent(\"web.brave.cacheOnly.error\", t.getClass().getSimpleName())"),
                "Brave cache-only rescue errors must not expose Java exception class names in TraceStore");
        assertTrue(naver.contains("TraceStore.putIfAbsent(\"web.naver.cacheOnly.error\", \"cache_only_failed\")"),
                "Naver cache-only rescue errors should use a stable operational reason");
        assertFalse(naver.contains("TraceStore.putIfAbsent(\"web.naver.cacheOnly.error\", t.getClass().getSimpleName())"),
                "Naver cache-only rescue errors must not expose Java exception class names in TraceStore");
    }

    @Test
    void pendingMemorySoakFlushFailureLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("vectorStoreService.triggerFlushIfDue();");
        int end = source.indexOf("} catch (Exception e) {", start);
        assertTrue(start >= 0 && end > start, "triggerFlushIfDue() catch span should be locatable");
        String flushCatch = source.substring(start, end);

        assertTrue(flushCatch.contains("TraceStore.inc(\"vectorstore.flush.failed\")"),
                "pending soak flush failure should leave a vectorstore flush breadcrumb");
        assertTrue(flushCatch.contains("[PENDING_SOAK] triggerFlushIfDue failed err={}")
                && flushCatch.contains("flushErr.getClass().getSimpleName()"),
                "pending soak flush failure should log only the exception class");
        assertFalse(flushCatch.contains("catch (Exception ignore)"),
                "pending soak flush failure must not be silently swallowed");
    }

    @Test
    void indexingSchedulerDoesNotAdvanceLastFetchTimeWhenVectorFlushFails() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/IndexingScheduler.java"),
                StandardCharsets.UTF_8);

        int flag = source.indexOf("boolean flushOk = false;");
        int flush = source.indexOf("vectorStoreService.flush();");
        int flagSet = source.indexOf("flushOk = true;", flush);
        int trace = source.indexOf("TraceStore.inc(\"indexing.flush.failed\")", flush);
        int guard = source.indexOf("if (flushOk)", flush);
        int advance = source.indexOf("lastFetchTime.set(LocalDateTime.now())", guard);
        assertTrue(flag >= 0, "scheduleIndexing() should track vector flush success");
        assertTrue(flush >= 0 && flagSet > flush, "flush success should be marked only after vectorStoreService.flush()");
        assertTrue(trace > flush, "vector flush failure should leave a TraceStore breadcrumb");
        assertTrue(guard > trace && advance > guard, "lastFetchTime should advance only inside the flushOk guard");
    }

    @Test
    void nightmareBreakerEvictsStaleDynamicStateAndPolicyEntries() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/resilience/NightmareBreaker.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("@Scheduled(fixedDelayString = \"${nightmare.breaker.evict-interval-ms:300000}\")"),
                "NightmareBreaker should have a small scheduled stale-state eviction hook");
        assertTrue(source.contains("void evictStaleStates()"),
                "NightmareBreaker stale-state eviction should be package-visible for focused tests");
        assertTrue(source.contains("lastActivityMs") && source.contains("void touch()"),
                "NightmareBreaker.State should track last activity for dynamic keys");
        assertTrue(source.contains("states.entrySet().removeIf"),
                "NightmareBreaker should trim stale dynamic state entries");
        assertTrue(source.contains("policyCache.keySet().removeIf(k -> !states.containsKey(k))"),
                "NightmareBreaker should trim policyCache entries after stale states are removed");
        assertTrue(source.contains("s.touch();"),
                "NightmareBreaker record paths should refresh state activity");
    }

    @Test
    void wiringPrecheckCoversHighRiskOptionalRagAndProviderBeans() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/boot/WiringPrecheckRunner.java"),
                StandardCharsets.UTF_8);

        String[] optionalLabels = {
                "DebugEventStore",
                "TraceSnapshotStore",
                "QueryTransformer",
                "NaverSearchService",
                "BraveSearchService",
                "NightmareBreaker",
                "DynamicRetrievalHandlerChain",
                "KnowledgeGraphHandler",
                "BrainStateService",
                "RagGraphExecutor",
                "EvidenceRepairHandler"
        };
        for (String label : optionalLabels) {
            assertTrue(source.contains("checkSingle(\"" + label + "\""),
                    "WiringPrecheckRunner should report optional bean presence for " + label);
        }
        assertTrue(source.contains("SafeRedactor.hashValue(name)"),
                "WiringPrecheckRunner should keep bean names redacted in startup diagnostics");
        assertTrue(source.contains("traceSuppressed(\"wiring.braveKeySnapshot\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"wiring.hybridAwaitSnapshot\", ignore);"));
    }

    @Test
    void queryRouteHandlerFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/QueryRouteHandler.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceRouteFailure(\"metadata_read\", text, metadataReadEx)"),
                "metadata read failures should leave a route trace breadcrumb");
        assertTrue(source.contains("traceRouteFailure(\"metadata_write\", text, metadataWriteEx)"),
                "metadata write failures should leave a route trace breadcrumb");
        assertTrue(source.contains("traceRouteFailure(\"decision\", text, decisionEx)"),
                "decision engine failures should leave a route trace breadcrumb");
        assertTrue(source.contains("TraceStore.inc(\"retrieval.route.\" + safeStage + \".failed\")"),
                "route failures should increment a stage-specific trace counter");
        assertTrue(source.contains("TraceStore.putIfAbsent(\"retrieval.route.queryHash\"")
                        && source.contains("SafeRedactor.hash12(text)")
                        && source.contains("TraceStore.putIfAbsent(\"retrieval.route.queryLength\""),
                "route failure trace should use query hash and length only");
        assertFalse(source.contains("catch (Exception ignore)"),
                "QueryRouteHandler must not silently swallow route failures");
    }

    @Test
    void searchCostGuardFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/SearchCostGuardHandler.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceCostGuardFailure(\"estimate\", text, estimateEx)"),
                "token estimation failures should leave a cost guard trace breadcrumb");
        assertTrue(source.contains("traceCostGuardFailure(\"relief\", text, reliefEx)"),
                "relief callback failures should leave a cost guard trace breadcrumb");
        assertTrue(source.contains("TraceStore.inc(\"retrieval.costGuard.\" + safeStage + \".failed\")"),
                "cost guard failures should increment a stage-specific trace counter");
        assertTrue(source.contains("TraceStore.putIfAbsent(\"retrieval.costGuard.queryHash\"")
                        && source.contains("SafeRedactor.hash12(text)")
                        && source.contains("TraceStore.putIfAbsent(\"retrieval.costGuard.queryLength\""),
                "cost guard failure trace should use query hash and length only");
        assertFalse(source.contains("catch (Exception ignore)"),
                "SearchCostGuardHandler must not silently swallow guard failures");
    }

    @Test
    void gradleHostSplitSupportsExternalBuildRootForAclBlockedWorkspaceBuilds() throws Exception {
        String source = Files.readString(Path.of("build.gradle.kts"), StandardCharsets.UTF_8);

        assertTrue(source.contains("AWX_BUILD_ROOT_DIR")
                        && source.contains("awx.buildRootDir"),
                "Gradle host-split should allow an explicit host-local build root override");
        assertTrue(source.contains("awxBuildRootDir")
                        && source.contains("awxProjectBuildDir(project)")
                        && source.contains("layout.buildDirectory.set(awxProjectBuildDir(project))"),
                "Gradle host-split should route each project build dir through the override-aware helper");
        assertTrue(source.contains("project.path.trim(':').replace(':', '-')"),
                "External build root should keep subproject output directories separated");
    }

    @Test
    void compositeQueryPreprocessorFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/pre/CompositeQueryContextPreprocessor.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("import com.example.lms.search.TraceStore;"),
                "Composite preprocessor should emit TraceStore breadcrumbs for fail-soft delegates");
        assertTrue(source.contains("tracePreprocessorFailure(\"enrich\"")
                        && source.contains("tracePreprocessorFailure(\"detect_domain\"")
                        && source.contains("tracePreprocessorFailure(\"infer_intent\"")
                        && source.contains("tracePreprocessorFailure(\"interaction_rules\""),
                "all preprocessor fail-soft paths should record the failed stage");
        assertTrue(source.contains("TraceStore.inc(\"query.preprocessor.\" + safeStage + \".failed\")"),
                "preprocessor failures should increment a stage-specific counter");
        assertTrue(source.contains("TraceStore.putIfAbsent(\"query.preprocessor.queryHash\"")
                        && source.contains("SafeRedactor.hash12(query)")
                        && source.contains("TraceStore.putIfAbsent(\"query.preprocessor.queryLength\""),
                "preprocessor failure trace should use query hash and length only");
        assertFalse(source.contains("catch (Exception ignore)"),
                "CompositeQueryContextPreprocessor must not silently swallow delegate failures");
    }

    @Test
    void guardrailQueryPreprocessorInternalFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/pre/GuardrailQueryPreprocessor.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("import com.example.lms.search.TraceStore;")
                        && source.contains("import com.example.lms.trace.SafeRedactor;"),
                "Guardrail preprocessor should emit redacted TraceStore breadcrumbs");
        assertTrue(source.contains("traceGuardrailFailure(\"cognitive_state\", original, stateEx)")
                        && source.contains("traceGuardrailFailure(\"typo_rewrite\", s, typoEx)")
                        && source.contains("traceGuardrailFailure(\"extract_cognitive_state\", q, stateEx)"),
                "guardrail internal fail-soft paths should record the failed stage");
        assertTrue(source.contains("TraceStore.inc(\"query.guardrail.\" + safeStage + \".failed\")"),
                "guardrail failures should increment a stage-specific counter");
        assertTrue(source.contains("TraceStore.putIfAbsent(\"query.guardrail.queryHash\"")
                        && source.contains("SafeRedactor.hash12(query)")
                        && source.contains("TraceStore.putIfAbsent(\"query.guardrail.queryLength\""),
                "guardrail failure trace should use query hash and length only");
        assertFalse(source.contains("catch (Exception ignore)"),
                "GuardrailQueryPreprocessor must not silently swallow internal failures");
    }

    @Test
    void webClientDiagnosticsFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/WebClientDiagnostics.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("import com.example.lms.search.TraceStore;"),
                "WebClient diagnostics should emit TraceStore breadcrumbs for its own fail-soft paths");
        assertTrue(source.contains("traceWebClientDiagnosticsFailure(\"request\", uri, requestEx)")
                        && source.contains("traceWebClientDiagnosticsFailure(\"response\", uri, responseEx)"),
                "request and response diagnostics failures should record their failed stage");
        assertTrue(source.contains("TraceStore.inc(\"webclient.diagnostics.\" + safeStage + \".failed\")"),
                "WebClient diagnostics failures should increment a stage-specific counter");
        assertTrue(source.contains("TraceStore.putIfAbsent(\"webclient.diagnostics.targetHash\"")
                        && source.contains("SafeRedactor.hash12(target)")
                        && source.contains("TraceStore.putIfAbsent(\"webclient.diagnostics.targetLength\""),
                "WebClient diagnostics failure trace should use target hash and length only");
        assertFalse(source.contains("catch (Exception ignore)"),
                "WebClientDiagnostics must not silently swallow diagnostics failures");
    }

    @Test
    void ragSubsystemNumericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/guard/GuardContext.java"),
                "return Integer.parseInt(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/guard/GuardContext.java"),
                "return (int) Long.parseLong(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/guard/GuardContext.java"),
                "return Long.parseLong(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/guard/GuardContext.java"),
                "return Double.parseDouble(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"),
                "return Long.parseLong(String.valueOf(value).trim());");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"),
                "return Double.parseDouble(String.valueOf(value).trim());");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmKAllocationTuner.java"),
                "double parsed = Double.parseDouble(String.valueOf(value).trim());");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/learn/NeedleKeptRatioRewardAspect.java"),
                "double parsed = Double.parseDouble(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmKallocLearningAspect.java"),
                "return Double.parseDouble(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/langgraph/RagGraphControlPolicy.java"),
                "return Integer.parseInt(String.valueOf(raw).trim());");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/langgraph/RagOrchestratorFacade.java"),
                "double value = Double.parseDouble(String.valueOf(raw).trim());");
    }

    private static void assertParserCatchNarrowed(Path path, String parserCall) throws Exception {
        String source = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, () -> "parser call should be locatable in " + path + ": " + parserCall);
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                () -> "numeric parser fallback must not hide non-parse failures in " + path);
        assertTrue(window.contains("catch (NumberFormatException"),
                () -> "numeric parser fallback should catch only NumberFormatException in " + path);
    }
}
