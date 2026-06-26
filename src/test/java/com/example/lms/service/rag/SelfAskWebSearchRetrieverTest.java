package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.NaverSearchService.SearchResult;
import com.example.lms.search.probe.BranchQualityProbe;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAskWebSearchRetrieverTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void selfAskLlmPromptTemplatesStayInKeywordPromptBuilder() throws Exception {
        String retriever = Files.readString(Path.of("main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));
        String promptBuilder = Files.readString(Path.of("main/java/com/example/lms/prompt/QueryKeywordPromptBuilder.java"));

        assertFalse(retriever.contains("private static final String SEARCH_PROMPT"));
        assertFalse(retriever.contains("private static final String FOLLOWUP_PROMPT"));
        assertFalse(retriever.contains("String prompt ="));
        assertFalse(retriever.contains("UserMessage.from(prompt)"));
        assertTrue(promptBuilder.contains("buildSelfAskSeedPrompt("));
        assertTrue(promptBuilder.contains("buildSelfAskFollowupPrompt("));
    }

    @Test
    void searchBudgetAccountingLivesOutsideRetrieverLargeFile() throws Exception {
        Path retrieverPath = Path.of("main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java");
        Path budgetPath = Path.of("main/java/com/example/lms/service/rag/SelfAskSearchBudget.java");

        String retriever = Files.readString(retrieverPath);

        assertTrue(Files.exists(budgetPath), "SelfAsk search budget helper should reduce retriever file size");
        String budget = Files.readString(budgetPath);
        assertTrue(retriever.contains("SelfAskSearchBudget budget = new SelfAskSearchBudget("));
        assertFalse(retriever.contains("private static final class SearchBudget"));
        assertTrue(budget.contains("final class SelfAskSearchBudget"));
        assertTrue(budget.contains("boolean tryConsume()"));
        assertTrue(budget.contains("int remaining()"));
    }

    @Test
    void numericParsingLivesOutsideRetrieverLargeFile() throws Exception {
        Path retrieverPath = Path.of("main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java");
        Path numbersPath = Path.of("main/java/com/example/lms/service/rag/SelfAskNumbers.java");

        String retriever = Files.readString(retrieverPath);

        assertTrue(Files.exists(numbersPath), "SelfAsk numeric helpers should reduce retriever file size");
        String numbers = Files.readString(numbersPath);
        assertTrue(retriever.contains("SelfAskNumbers.parseDouble("));
        assertTrue(retriever.contains("SelfAskNumbers.parseLong("));
        assertTrue(retriever.contains("SelfAskNumbers.clampInt("));
        assertTrue(retriever.contains("SelfAskNumbers.clampDouble("));
        assertFalse(retriever.contains("private static double parseDouble("));
        assertFalse(retriever.contains("private static long parseLong("));
        assertFalse(retriever.contains("private static int clampInt("));
        assertFalse(retriever.contains("private static double clampDouble("));
        assertFalse(retriever.contains("catch (Exception ignore) { traceSuppressed(\"meta.int\", ignore); }"));
        assertFalse(retriever.contains("catch (Exception ignore) { traceSuppressed(\"meta.long\", ignore); }"));
        assertFalse(retriever.contains("catch (Exception ignore) { traceSuppressed(\"meta.double\", ignore); }"));
        assertTrue(retriever.contains("catch (NumberFormatException ignore) { traceSuppressed(\"meta.int\", ignore); }"));
        assertTrue(retriever.contains("catch (NumberFormatException ignore) { traceSuppressed(\"meta.long\", ignore); }"));
        assertTrue(retriever.contains("catch (NumberFormatException ignore) { traceSuppressed(\"meta.double\", ignore); }"));
        assertTrue(retriever.contains("TraceStore.put(\"selfask.suppressed.\" + safeStage + \".errorType\", errorType(ignored));"));
        assertTrue(retriever.contains("return ignored instanceof NumberFormatException ? \"invalid_number\""));
        assertTrue(numbers.contains("final class SelfAskNumbers"));
        assertFalse(numbers.contains("catch (Exception ignore)"));
        assertTrue(numbers.contains("catch (NumberFormatException ignore)"));
    }

    @Test
    void selfAskNumbersRejectNonFiniteValues() {
        assertEquals(0.25d, SelfAskNumbers.parseDouble(Double.POSITIVE_INFINITY, 0.25d), 0.0001d);
        assertEquals(0.50d, SelfAskNumbers.parseDouble("Infinity", 0.50d), 0.0001d);
        assertEquals(7L, SelfAskNumbers.parseLong(Double.POSITIVE_INFINITY, 7L));
    }

    @Test
    void selfAskMetadataParsersRejectNonFiniteNumbers() throws Exception {
        Method metaInt = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        Method metaLong = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "metaLong", Map.class, String.class, long.class);
        Method metaDouble = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "metaDouble", Map.class, String.class, double.class);
        metaInt.setAccessible(true);
        metaLong.setAccessible(true);
        metaDouble.setAccessible(true);

        Map<String, Object> meta = Map.of(
                "i", Double.POSITIVE_INFINITY,
                "l", Double.POSITIVE_INFINITY,
                "d", "Infinity");

        assertEquals(3, metaInt.invoke(null, meta, "i", 3));
        assertEquals(7L, metaLong.invoke(null, meta, "l", 7L));
        assertEquals(0.5d, (Double) metaDouble.invoke(null, meta, "d", 0.5d), 0.0001d);
        assertEquals("invalid_number", TraceStore.get("selfask.suppressed.meta.int.errorType"));
        assertEquals("invalid_number", TraceStore.get("selfask.suppressed.meta.long.errorType"));
        assertEquals("invalid_number", TraceStore.get("selfask.suppressed.meta.double.errorType"));
    }

    @Test
    void selfAskTraceNumericParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertParserCatchNarrowed(source, "private static double zero100LaneWeight(String lane)");
        assertParserCatchNarrowed(source, "private static int traceInt(String key, int defaultValue)");
        assertParserCatchNarrowed(source, "private static long traceLong(String key, long defaultValue)");
    }

    @Test
    void selfAskWebRetrieverFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertSelfAskWebStage(source, "refreshRewriteRisk.trace");
        assertSelfAskWebStage(source, "zero100.queryBurst.budget");
        assertSelfAskWebStage(source, "zero100.queryBurst.anchorDroppedCount");
        assertSelfAskWebStage(source, "zero100.queryBurst.seedTrace");
        assertSelfAskWebStage(source, "zero100.rollover.events");
        assertSelfAskWebStage(source, "zero100.branch.budgetRollover");
        assertSelfAskWebStage(source, "zero100.branch.callBudget");
        assertSelfAskWebInvalidNumberStage(source, "zero100LaneWeight");
        assertSelfAskWebInvalidNumberStage(source, "traceInt");
        assertSelfAskWebInvalidNumberStage(source, "traceLong");
        assertSelfAskWebStage(source, "traceRequeryAttempt");
        assertSelfAskWebStage(source, "traceRequerySummary");
        assertSelfAskWebStage(source, "putTraceMetadata");
        assertSelfAskWebStage(source, "pruneSelfAskSnippet");
    }

    private static void assertSelfAskWebStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[SelfAskWebSearchRetriever] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing SelfAskWebSearchRetriever fail-soft stage: " + stage);
    }

    private static void assertSelfAskWebInvalidNumberStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[SelfAskWebSearchRetriever] fail-soft stage={} errorType={}\",")
                        && source.contains("\"" + stage + "\", \"invalid_number\""),
                () -> "missing SelfAskWebSearchRetriever invalid_number fail-soft stage: " + stage);
    }

    @Test
    void globalDisabledWithoutExplicitPlanOverrideDoesNotCallProvider() {
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", false);

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(
                "simple query",
                Map.of("enableSelfAsk", "true")));

        assertTrue(out.isEmpty());
        assertEquals(0, provider.calls.get());
        assertEquals("global-disabled-no-plan-override", TraceStore.get("selfask.disabled.reason"));
        assertEquals(Boolean.FALSE, TraceStore.get("selfask.planOverride.enabled"));
    }

    @Test
    void globalDisabledWithExplicitPlanOverrideRunsFailSoftSearchPath() {
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", false);

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(
                "short query",
                Map.of(
                        "selfask.enabled", "true",
                        "selfask.planOverride.reason", "expand.selfAsk.count",
                        "enableSelfAsk", "false")));

        assertEquals(1, provider.calls.get());
        assertTrue(out.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.planOverride.enabled"));
        assertEquals("expand.selfAsk.count", TraceStore.get("selfask.planOverride.reason"));
    }

    @Test
    void planOverrideReasonTraceUsesSafeLabel() {
        String rawReason = "expand.selfAsk.count ownerToken=raw-owner-secret";
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", false);

        retriever.retrieve(QueryUtils.buildQuery(
                "short query",
                Map.of(
                        "selfask.enabled", "true",
                        "selfask.planOverride.reason", rawReason,
                        "enableSelfAsk", "false")));

        Object reason = TraceStore.get("selfask.planOverride.reason");
        assertTrue(String.valueOf(reason).startsWith("hash:"));
        assertFalse(String.valueOf(reason).contains("ownerToken"));
        assertFalse(String.valueOf(reason).contains("raw-owner-secret"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawReason));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceRequeryAttemptStoresOnlyRedactedCounts() {
        String rawSeed = "raw sensitive requery text should not appear";
        BranchQualityProbe.BranchQualityMetrics metric = new BranchQualityProbe.BranchQualityMetrics(
                "RC",
                "relation_hypothesis",
                3,
                0.75d,
                0.50d,
                0.20d,
                0.25d,
                0.80d,
                7,
                0.80d,
                0.50d,
                4,
                0.20d,
                0.45d,
                BranchQualityProbe.BranchAction.REWRITE_RETRY,
                "contribution_low");

        SelfAskWebSearchRetriever.traceRequeryAttempt(
                "RC",
                rawSeed,
                1.7d,
                0.42d,
                900L,
                7,
                3,
                1,
                false,
                "zero-results",
                metric,
                true);

        Object attemptsObj = TraceStore.get("selfask.requery.attempts");
        assertTrue(attemptsObj instanceof List<?>);
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) attemptsObj;
        Map<String, Object> event = attempts.get(0);

        assertEquals("RC", event.get("lane"));
        assertEquals(7, event.get("requestedTopK"));
        assertEquals(3, event.get("returnedCount"));
        assertEquals(1, event.get("afterFilterCount"));
        assertEquals("zero-results", event.get("failureClass"));
        assertEquals(true, event.get("retry"));
        assertEquals("RC", event.get("branchId"));
        assertEquals("relation_hypothesis", event.get("intentAxis"));
        assertEquals(3, event.get("retrievedCount"));
        assertEquals(0.75d, event.get("duplicateRatio"));
        assertEquals(0.80d, event.get("riskPenalty"));
        assertEquals("REWRITE_RETRY", event.get("action"));
        assertTrue(event.containsKey("seedHash12"));
        assertFalse(String.valueOf(attemptsObj).contains(rawSeed));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceRequeryAttemptRedactsBranchMetricReason() {
        String secretShapedReason = "retry branch api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        BranchQualityProbe.BranchQualityMetrics metric = new BranchQualityProbe.BranchQualityMetrics(
                "RC",
                "relation_hypothesis",
                1,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                1.0d,
                0,
                0.0d,
                0.0d,
                1,
                0.20d,
                0.70d,
                BranchQualityProbe.BranchAction.REWRITE_RETRY,
                secretShapedReason);

        SelfAskWebSearchRetriever.traceRequeryAttempt(
                "RC",
                "safe seed",
                1.0d,
                0.20d,
                900L,
                4,
                0,
                0,
                true,
                "zero-results",
                metric,
                true);

        List<Map<String, Object>> attempts = (List<Map<String, Object>>) TraceStore.get("selfask.requery.attempts");
        String renderedReason = String.valueOf(attempts.get(0).get("reason"));

        assertFalse(renderedReason.contains("sk-"), renderedReason);
    }

    @Test
    void selfAskContentPreservesLaneAndHashMetadata() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertTrue(source.contains("md.put(\"retrieval_lane\", lane);"));
        assertTrue(source.contains("md.put(\"retrieval_lane_role\", laneRole(lane));"));
        assertTrue(source.contains("md.put(\"branchId\", lane);"));
        assertTrue(source.contains("md.put(\"intentAxis\", laneRole(lane));"));
        assertTrue(source.contains("md.put(\"retrieval_query_hash12\", SafeRedactor.hash12(retrievalQuery));"));
        assertTrue(source.contains("md.put(\"parent_query_hash12\", SafeRedactor.hash12(parentQuery));"));
        assertTrue(source.contains("case") || source.contains("\"BQ\""));
    }

    @Test
    void selfAskLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()")
                        || line.contains(".toString()")
                        || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
    }

    @Test
    void selfAskRetrieverDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "self-ask retrieval fail-soft helpers need safe breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void llmKeywordFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertFalse(source.contains("LLM keyword generation failed: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("LLM follow-up generation failed: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("LLM keyword generation failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("LLM follow-up generation failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
    }

    @Test
    void riskEmergentReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertFalse(source.contains("meta.put(\"resource.riskEmergentReason\", risk.emergentAdjustment().reason());"));
        assertFalse(source.contains("TraceStore.put(\"resource.riskEmergentReason\", risk.emergentAdjustment().reason());"));
        assertFalse(source.contains("TraceStore.put(\"ml.risk.emergent.reason\", risk.emergentAdjustment().reason());"));
        assertFalse(source.contains("\"reason\", risk.emergentAdjustment().reason()"));
        assertFalse(source.contains(
                "SafeRedactor.safeMessage(risk.emergentAdjustment().reason(), 120)"));
        assertFalse(source.contains("event.put(\"reason\", SafeRedactor.safeMessage(branchMetric.reason(), 120));"));
        assertTrue(source.contains(
                "SafeRedactor.traceLabelOrFallback(risk.emergentAdjustment().reason(), \"unknown\")"));
        assertTrue(source.contains("meta.put(\"resource.riskEmergentReason\", safeEmergentReason);"));
        assertTrue(source.contains("TraceStore.put(\"resource.riskEmergentReason\", safeEmergentReason);"));
        assertTrue(source.contains("TraceStore.put(\"ml.risk.emergent.reason\", safeEmergentReason);"));
        assertTrue(source.contains("\"reason\", safeEmergentReason"));
        assertTrue(source.contains(
                "event.put(\"reason\", SafeRedactor.traceLabelOrFallback(branchMetric.reason(), \"unknown\"));"));
    }

    @Test
    void retrySkipReasonRejectsSameAndVisitedQueriesBeforeBudgetUse() throws Exception {
        SelfAskWebSearchRetriever retriever = new SelfAskWebSearchRetriever(null, null, null, null);
        HashSet<String> visited = new HashSet<>();
        visited.add("alreadyvisited");

        assertEquals("same_parent_query", ReflectionTestUtils.invokeMethod(
                retriever, "retrySkipReason", "parent query", "branch query", "parent query", visited));
        assertEquals("same_branch_query", ReflectionTestUtils.invokeMethod(
                retriever, "retrySkipReason", "parent query", "branch query", "branch query", visited));
        assertEquals("already_visited", ReflectionTestUtils.invokeMethod(
                retriever, "retrySkipReason", "parent query", "branch query", "already visited", visited));

        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));
        int skipIdx = source.indexOf("String skipReason = retrySkipReason");
        int budgetIdx = source.indexOf("if (!budget.tryConsume())", skipIdx);
        assertTrue(skipIdx > 0);
        assertTrue(budgetIdx > skipIdx);
        assertTrue(source.contains("searchExecutor.submit(() -> safeSearchAttempt(retryQuery, retryTopK))"));
        assertTrue(source.contains("long retryWaitMs = zero100LaneTimeboxMs(lane, waitMs);"));
        assertTrue(source.contains("getWithHardTimeout(retryFuture, retryWaitMs, retryQuery)"));
        assertTrue(source.contains("qHash="));
    }

    @Test
    void zero100LaneBudgetAndTimeboxWiringIsSourceStable() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertTrue(source.contains("java.util.Map<String, LaneBudget> laneBudgets = zero100LaneBudgets(maxApiCallsPerQuery);"));
        assertTrue(source.contains("List<Future<SearchAttempt>> futures = new ArrayList<>();"));
        assertTrue(source.contains("SearchAttemptMeta attemptMeta = i < futureMeta.size()"));
        assertTrue(source.contains("? futureMeta.get(i)"));
        assertTrue(source.contains("String kw = attemptMeta.query();"));
        assertTrue(source.contains("searchExecutor.submit(() -> safeSearchAttempt(kw, topKForKw))"));
        assertTrue(source.contains("\"skipped:lane_budget_exhausted\""));
        assertTrue(source.contains("zero100LaneTimeboxMs(laneForKw, reqPerRequestTimeoutMs)"));
        assertTrue(source.contains("TraceStore.append(\"zero100.branch.budgetRollover.events\""));
        assertFalse(source.contains("String kw = currentKeywords.get(i);"));
        assertFalse(source.contains("cancel(true)"));
    }

    @Test
    void safeSearchAttemptPreservesRateLimitFailureClass() throws Exception {
        SelfAskWebSearchRetriever retriever = newRetriever(new ThrowingProvider("HTTP 429 rate limit"));

        Object attempt = invokeSafeSearchAttempt(retriever, "raw sensitive rate limit query", 3);

        assertEquals("rate-limit", recordValue(attempt, "failureClass"));
        assertEquals(Boolean.TRUE, recordValue(attempt, "fallback"));
        assertTrue(((List<?>) recordValue(attempt, "results")).isEmpty());
        assertFalse(String.valueOf(TraceStore.get("zero100.rollover.events")).contains("raw sensitive"));

        TraceStore.put("zero100.enabled", true);
        assertTrue(SelfAskWebSearchRetriever.traceZero100Rollover(
                "BQ",
                String.valueOf(recordValue(attempt, "failureClass")),
                1));
        assertTrue(String.valueOf(TraceStore.get("zero100.rollover.events")).contains("rate-limit"));
    }

    @Test
    void safeSearchAttemptNormalizesCancellationFailureClass() throws Exception {
        String rawQuery = "raw cancellation query ownerToken=fake-token";
        SelfAskWebSearchRetriever retriever = newRetriever(new CancellingProvider());

        Object attempt = invokeSafeSearchAttempt(retriever, rawQuery, 3);

        assertEquals("cancelled", recordValue(attempt, "failureClass"));
        assertEquals(Boolean.TRUE, recordValue(attempt, "fallback"));
        assertTrue(((List<?>) recordValue(attempt, "results")).isEmpty());
        assertFalse(String.valueOf(recordValue(attempt, "failureClass")).contains("CancellationException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void safeSearchAttemptPreservesProviderDisabledAndMissingKeyFailures() throws Exception {
        Object disabled = invokeSafeSearchAttempt(
                newRetriever(new ThrowingProvider("provider disabled by configuration")),
                "raw disabled query",
                3);
        Object missingKey = invokeSafeSearchAttempt(
                newRetriever(new ThrowingProvider("missing api key")),
                "raw missing key query",
                3);

        assertEquals("provider-disabled", recordValue(disabled, "failureClass"));
        assertEquals("missing-key-or-unauthorized", recordValue(missingKey, "failureClass"));
        assertEquals(Boolean.TRUE, recordValue(disabled, "fallback"));
        assertEquals(Boolean.TRUE, recordValue(missingKey, "fallback"));

        TraceStore.put("zero100.enabled", true);
        assertTrue(SelfAskWebSearchRetriever.traceZero100Rollover("BQ", "provider_disabled", 1));
        assertTrue(SelfAskWebSearchRetriever.traceZero100Rollover("ER", "missing_external_key", 1));
        String events = String.valueOf(TraceStore.get("zero100.rollover.events"));
        assertTrue(events.contains("provider-disabled"));
        assertTrue(events.contains("missing-key-or-unauthorized"));
        assertFalse(events.contains("raw disabled query"));
        assertFalse(events.contains("raw missing key query"));
    }

    @Test
    void zero100LaneBudgetsNeverExceedGlobalCapForSmallTotals() throws Exception {
        Map<String, Object> one = zero100Budgets(1);
        Map<String, Object> two = zero100Budgets(2);

        assertEquals(1, sumRemaining(one));
        assertEquals(2, sumRemaining(two));
        assertEquals(1, sumInitial(one));
        assertEquals(2, sumInitial(two));
    }

    @Test
    @SuppressWarnings("unchecked")
    void zero100LaneBudgetRolloverDoesNotInflateTargetWhenSourceEmpty() throws Exception {
        Map<String, Object> budgets = zero100Budgets(1);
        String sourceLane = null;
        for (Map.Entry<String, Object> entry : budgets.entrySet()) {
            if (remaining(entry.getValue()) == 0) {
                sourceLane = entry.getKey();
                break;
            }
        }
        if (sourceLane == null) {
            throw new AssertionError("expected at least one zero-call lane");
        }
        String targetLane = null;
        for (String lane : budgets.keySet()) {
            if (!lane.equals(sourceLane)) {
                targetLane = lane;
                break;
            }
        }
        if (targetLane == null) {
            throw new AssertionError("expected target lane");
        }
        int targetBefore = remaining(budgets.get(targetLane));

        Method move = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "moveOneLaneBudget", Map.class, String.class, String.class, String.class, int.class);
        move.setAccessible(true);
        move.invoke(null, budgets, sourceLane, targetLane, "rate_limit_local", 1);

        assertEquals(0, remaining(budgets.get(sourceLane)));
        assertEquals(targetBefore, remaining(budgets.get(targetLane)));
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) TraceStore.get("zero100.branch.budgetRollover.events");
        Map<String, Object> event = events.get(0);
        assertEquals(sourceLane, event.get("from"));
        assertEquals(targetLane, event.get("to"));
        assertEquals("rate-limit", event.get("failureClass"));
        assertEquals(0, event.get("movedCalls"));
        assertEquals(1, event.get("remainingGlobalBudget"));
        assertEquals(0, event.get("fromRemaining"));
        assertEquals(targetBefore, event.get("toRemaining"));
    }

    @Test
    void zero100KeywordOrderingPrioritizesActiveLane() {
        List<String> ordered = SelfAskWebSearchRetriever.orderZero100Keywords(
                List.of("strict anchor", "relaxed anchor", "explore anchor"),
                Map.of(
                        "strictanchor", "BQ",
                        "relaxedanchor", "ER",
                        "exploreanchor", "RC"),
                "RC");

        assertEquals("explore anchor", ordered.get(0));
    }

    @Test
    void zero100BurstSeedIncludesSerpApiAndTavilySkipReasons() throws Exception {
        TraceStore.put("web.serpapi.skipped.reason", "quota_exhausted");
        TraceStore.put("web.tavily.skipped.reason", "missing_tavily_api_key");

        String seed = zero100BurstSeed("parent query");

        assertTrue(seed.contains("quota_exhausted"), seed);
        assertTrue(seed.contains("missing_tavily_api_key"), seed);
        assertFalse(seed.contains("raw sensitive query should not leak"), seed);
    }

    @Test
    @SuppressWarnings("unchecked")
    void zero100RolloverTraceIsBoundedAndRedacted() {
        TraceStore.put("zero100.enabled", true);

        SelfAskWebSearchRetriever.traceZero100Rollover("RC", "timeout", 2);

        Object eventsObj = TraceStore.get("zero100.rollover.events");
        assertTrue(eventsObj instanceof List<?>);
        List<Map<String, Object>> events = (List<Map<String, Object>>) eventsObj;
        Map<String, Object> event = events.get(0);

        assertEquals(Boolean.TRUE, event.get("applied"));
        assertEquals("RC", event.get("currentLane"));
        assertEquals("BQ", event.get("nextLane"));
        assertEquals("timeout", event.get("failureClass"));
        assertEquals(1, event.get("movedCalls"));
        assertEquals(2, event.get("remainingGlobalBudget"));
        assertFalse(String.valueOf(eventsObj).contains("raw sensitive requery text"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void zero100RolloverAppliesNextLaneOrderingWithoutGrowingGlobalBudget() {
        TraceStore.put("zero100.enabled", true);
        SelfAskWebSearchRetriever.Zero100RolloverState state =
                new SelfAskWebSearchRetriever.Zero100RolloverState("BQ");

        boolean applied = SelfAskWebSearchRetriever.traceZero100Rollover(state, "BQ", "rate-limit", 1, 2);
        List<String> ordered = SelfAskWebSearchRetriever.orderZero100Keywords(
                List.of("strict anchor", "relaxed anchor", "explore anchor"),
                Map.of(
                        "strictanchor", "BQ",
                        "relaxedanchor", "ER",
                        "exploreanchor", "RC"),
                state.preferredLane(),
                state.cooledLane());

        assertTrue(applied);
        assertEquals("ER", state.preferredLane());
        assertEquals("BQ", state.cooledLane());
        assertEquals(1, state.movedCalls());
        assertEquals("relaxed anchor", ordered.get(0));
        assertEquals("strict anchor", ordered.get(2));

        List<Map<String, Object>> events = (List<Map<String, Object>>) TraceStore.get("zero100.rollover.events");
        Map<String, Object> event = events.get(0);
        assertEquals(1, event.get("remainingGlobalBudget"));
        assertEquals(2, event.get("depth"));
        assertEquals(1, event.get("movedCalls"));
    }

    @Test
    void zero100QueryBurstTraceUsesHashesOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertTrue(source.contains("zero100.queryBurst.seedHashes"));
        assertTrue(source.contains("SafeRedactor.hash12(norm)"));
        assertFalse(source.contains("traceString(\"zero100.mpIntent\""));
        assertFalse(source.contains("TraceStore.put(\"zero100.queryBurst.seeds\""));
    }

    @Test
    void hardTimeoutTraceUsesCancelFalseAndRedactedQueryHash() throws Exception {
        String rawQuery = "raw timeout query with api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        SelfAskWebSearchRetriever retriever = newRetriever(new CountingProvider());
        TimeoutRecordingFuture future = new TimeoutRecordingFuture();

        Object attempt = invokeHardTimeout(retriever, future, 25L, rawQuery);

        assertEquals("timeout", recordValue(attempt, "failureClass"));
        assertEquals(Boolean.TRUE, recordValue(attempt, "fallback"));
        assertEquals(1, future.cancelCalls.get());
        assertEquals(Boolean.FALSE, future.lastMayInterruptIfRunning);
        assertEquals("hard_timeout", TraceStore.get("selfask.timeout.stage"));
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.timeout.cancelSuppressed"));
        assertEquals(Boolean.FALSE, TraceStore.get("selfask.timeout.cancelInterrupt"));
        assertEquals(25L, ((Number) TraceStore.get("selfask.timeout.timeoutMs")).longValue());
        assertTrue(String.valueOf(TraceStore.get("selfask.timeout.queryHash12")).matches("[0-9a-f]{12}"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void zero100BudgetReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertFalse(source.contains("TraceStore.put(\"zero100.queryBurst.budget.reason\", decision.reason());"));
        assertFalse(source.contains(
                "TraceStore.put(\"zero100.queryBurst.budget.reason\", SafeRedactor.safeMessage(decision.reason(), 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"zero100.queryBurst.budget.reason\", SafeRedactor.traceLabelOrFallback(decision.reason(), \"unknown\"));"));
    }

    @Test
    void zero100BudgetGovernorUsesFreshnessQueryNotTextureOnlineFlag() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertTrue(source.contains("retrievalBudgetGovernor.decide(anchorResult, textureLookup.hitRate(), freshnessQuery,"));
        assertFalse(source.contains("retrievalBudgetGovernor.decide(anchorResult, textureLookup.hitRate(), textureLookup.onlineSearchNeeded()"));
    }

    @Test
    void logicDagDisabledKeepsBranchSeedOrder() throws Exception {
        SelfAskWebSearchRetriever retriever = newRetriever(new CountingProvider());
        enableThreeWayPlanner(retriever);
        ReflectionTestUtils.setField(retriever, "logicDagEnabled", false);

        List<?> seeds = invokeBranch3Seeds(retriever, "who is Ada and what aliases are related");

        assertEquals(List.of("BQ", "ER", "RC"), laneSeedLanes(seeds));
        assertEquals(Boolean.FALSE, TraceStore.get("selfask.logicDag.enabled"));
        assertEquals("disabled", TraceStore.get("selfask.logicDag.failureClass"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledLogicDagOrdersSearchAttemptsByDagWithoutRawQueryTrace() {
        String rawQuery = "who is Ada Lovelace and which aliases are related in computing history";
        RecordingProvider provider = new RecordingProvider(rawQuery);
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        enableThreeWayPlanner(retriever);
        ReflectionTestUtils.setField(retriever, "logicDagEnabled", true);
        ReflectionTestUtils.setField(retriever, "searchExecutor", executor);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", true);
        ReflectionTestUtils.setField(retriever, "firstHitStopThreshold", 99);

        try {
            retriever.retrieve(QueryUtils.buildQuery(
                    rawQuery,
                    Map.of("enableSelfAsk", "true")));
        } finally {
            executor.shutdownNow();
        }

        List<Map<String, Object>> attempts =
                (List<Map<String, Object>>) TraceStore.get("selfask.requery.attempts");
        assertEquals(List.of("ER", "BQ", "RC"), attempts.stream()
                .map(row -> String.valueOf(row.get("lane")))
                .limit(3)
                .toList());
        assertEquals("entity_first", TraceStore.get("selfask.logicDag.dependencyMode"));
        assertEquals(List.of("ER", "BQ", "RC"), TraceStore.get("selfask.logicDag.topologicalOrder"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
        assertTrue(provider.calls.get() >= 4);
    }

    @Test
    void logicDagPlannerFailurePreservesBranchSeedOrder() throws Exception {
        SelfAskWebSearchRetriever retriever = new FailingLogicDagRetriever(new CountingProvider());
        enableThreeWayPlanner(retriever);
        ReflectionTestUtils.setField(retriever, "logicDagEnabled", true);

        List<?> seeds = invokeBranch3Seeds(retriever, "who is Ada and what aliases are related");

        assertEquals(List.of("BQ", "ER", "RC"), laneSeedLanes(seeds));
        assertEquals("cycle_detected", TraceStore.get("selfask.logicDag.failureClass"));
    }

    private static SelfAskWebSearchRetriever newRetriever(WebSearchProvider provider) {
        SelfAskWebSearchRetriever retriever = new SelfAskWebSearchRetriever(provider, null, null, null);
        ReflectionTestUtils.setField(retriever, "webTopK", 3);
        ReflectionTestUtils.setField(retriever, "overallTopK", 3);
        ReflectionTestUtils.setField(retriever, "firstHitStopThreshold", 1);
        ReflectionTestUtils.setField(retriever, "maxDepth", 1);
        ReflectionTestUtils.setField(retriever, "perRequestTimeoutMs", 500);
        ReflectionTestUtils.setField(retriever, "selfAskTimeoutSec", 1);
        ReflectionTestUtils.setField(retriever, "finalTopK", 3);
        ReflectionTestUtils.setField(retriever, "laneTopK", 1);
        ReflectionTestUtils.setField(retriever, "followupsPerLevel", 1);
        ReflectionTestUtils.setField(retriever, "maxApiCallsPerQuery", 8);
        return retriever;
    }

    private static void enableThreeWayPlanner(SelfAskWebSearchRetriever retriever) {
        ReflectionTestUtils.setField(retriever, "threeWayEnabled", true);
        ReflectionTestUtils.setField(retriever, "threeWayPlanner", new SelfAskPlanner(null, null));
        ReflectionTestUtils.setField(retriever, "logicDagMaxNodes", 5);
        ReflectionTestUtils.setField(retriever, "logicDagPruneDuplicates", true);
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }

    private static List<?> invokeBranch3Seeds(SelfAskWebSearchRetriever retriever, String query) throws Exception {
        Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "branch3Seeds", String.class, long.class, boolean.class, double.class, Map.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(retriever, query, 1000L, true, 0.2d, Map.of());
    }

    private static List<String> laneSeedLanes(List<?> seeds) {
        return seeds.stream()
                .map(seed -> {
                    try {
                        return String.valueOf(recordValue(seed, "lane"));
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();
    }

    private static Object invokeSafeSearchAttempt(
            SelfAskWebSearchRetriever retriever,
            String query,
            int topK) throws Exception {
        Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod("safeSearchAttempt", String.class, int.class);
        method.setAccessible(true);
        return method.invoke(retriever, query, topK);
    }

    private static Object invokeHardTimeout(
            SelfAskWebSearchRetriever retriever,
            Future<?> future,
            long timeoutMs,
            String query) throws Exception {
        Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "getWithHardTimeout", Future.class, long.class, String.class);
        method.setAccessible(true);
        return method.invoke(retriever, future, timeoutMs, query);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> zero100Budgets(int totalCalls) throws Exception {
        Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod("zero100LaneBudgets", int.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(null, totalCalls);
    }

    private static int sumRemaining(Map<String, Object> budgets) throws Exception {
        int total = 0;
        for (Object budget : budgets.values()) {
            total += remaining(budget);
        }
        return total;
    }

    private static int sumInitial(Map<String, Object> budgets) throws Exception {
        int total = 0;
        for (Object budget : budgets.values()) {
            total += ((Number) recordValue(budget, "initial")).intValue();
        }
        return total;
    }

    private static String zero100BurstSeed(String parentQuery) throws Exception {
        Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "zero100BurstSeed", String.class, java.util.List.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, parentQuery, List.of(), "RC");
    }

    private static int remaining(Object budget) throws Exception {
        return ((Number) recordValue(budget, "remaining")).intValue();
    }

    private static Object recordValue(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private static final class ThrowingProvider implements WebSearchProvider {
        private final String message;

        private ThrowingProvider(String message) {
            this.message = message;
        }

        @Override
        public List<String> search(String query, int topK) {
            throw new IllegalStateException(message);
        }

        @Override
        public SearchResult searchWithTrace(String query, int topK) {
            return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "throwing";
        }
    }

    private static final class CountingProvider implements WebSearchProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<String> search(String query, int topK) {
            calls.incrementAndGet();
            return List.of();
        }

        @Override
        public SearchResult searchWithTrace(String query, int topK) {
            return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "counting";
        }
    }

    private static final class CancellingProvider implements WebSearchProvider {

        @Override
        public List<String> search(String query, int topK) {
            throw new CancellationException("cancelled ownerToken=fake-token");
        }

        @Override
        public SearchResult searchWithTrace(String query, int topK) {
            return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "cancelling";
        }
    }

    private static final class TimeoutRecordingFuture implements Future<Object> {
        final AtomicInteger cancelCalls = new AtomicInteger();
        Boolean lastMayInterruptIfRunning;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            lastMayInterruptIfRunning = mayInterruptIfRunning;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelCalls.get() > 0;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            throw new TimeoutException("forced hard timeout");
        }
    }

    private static final class RecordingProvider implements WebSearchProvider {
        private final String originalQuery;
        final AtomicInteger calls = new AtomicInteger();

        private RecordingProvider(String originalQuery) {
            this.originalQuery = originalQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            int call = calls.incrementAndGet();
            if (originalQuery.equals(query)) {
                return List.of();
            }
            return List.of("logic-dag-snippet-" + call);
        }

        @Override
        public SearchResult searchWithTrace(String query, int topK) {
            return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "recording";
        }
    }

    private static final class FailingLogicDagRetriever extends SelfAskWebSearchRetriever {
        private FailingLogicDagRetriever(WebSearchProvider provider) {
            super(provider, null, null, null);
        }

        @Override
        LogicRagPlanner logicRagPlanner() {
            return new LogicRagPlanner(true);
        }
    }
}
