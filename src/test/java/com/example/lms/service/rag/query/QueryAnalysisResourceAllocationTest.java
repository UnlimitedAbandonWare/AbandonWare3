package com.example.lms.service.rag.query;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryAnalysisResourceAllocationTest {

    @Test
    void queryAnalysisLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/query/QueryAnalysisService.java"),
                StandardCharsets.UTF_8);
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage()"));
        assertTrue(source.contains("[AWX][query-analysis] llm analysis failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(source.contains("[AWX][query-analysis] analysis failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(source.contains("[AWX][query-analysis] json parsing failed failureReason={} errorType={} responseHash12={} responseLength={}"));
    }

    @Test
    void queryAnalysisCompletionLogDoesNotPrintRawEntities() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/query/QueryAnalysisService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("entityCount={}, entityHash={}"));
        assertTrue(!source.contains("entities={}, exploration"));
        assertTrue(!source.contains("result.entities(),"));
    }

    @Test
    void queryAnalysisLlmCallUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/query/QueryAnalysisService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String queryAnalysisPrompt = String.format(ANALYSIS_PROMPT, userQuery);"));
        assertTrue(source.contains("UserMessage.from(queryAnalysisPrompt)"));
    }

    @Test
    void queryAnalysisLlmCallFailureLeavesRedactedTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/query/QueryAnalysisService.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("String queryAnalysisPrompt = String.format(ANALYSIS_PROMPT, userQuery);");
        int end = source.indexOf("return parseJsonResponse(userQuery, response, quickExploration, quickFresh);", start);
        assertTrue(start >= 0 && end > start, "query-analysis LLM call span should be locatable");
        String llmCall = source.substring(start, end);

        assertTrue(llmCall.contains("TraceStore.inc(\"queryAnalysis.llm.failed\")"),
                "query-analysis LLM failure should increment a trace breadcrumb");
        assertTrue(llmCall.contains("TraceStore.putIfAbsent(\"queryAnalysis.bypassed\", \"true\")"),
                "query-analysis LLM failure should mark the analysis stage as bypassed");
        assertTrue(llmCall.contains("TraceStore.putIfAbsent(\"queryAnalysis.reason\", safeErrorType(llmEx))"),
                "query-analysis LLM failure should record only the exception type");
        assertTrue(llmCall.contains("SafeRedactor.hash12(userQuery)")
                        && llmCall.contains("userQuery == null ? 0 : userQuery.length()"),
                "query-analysis LLM failure logging should use query hash and length only");
        assertFalse(llmCall.contains("catch (Exception ignore)"),
                "query-analysis LLM failure must not be silently swallowed");
    }

    @Test
    void highValueQueryUsesRiskDampingAndHigherEvidenceThreshold() {
        QueryAnalysisResult result = new QueryAnalysisResult(
                "DGX Spark 구매 전 스펙 가격 리스크 비교",
                QueryAnalysisResult.QueryIntent.COMPARE,
                List.of("DGX Spark"),
                List.of("가격", "리스크"),
                true,
                true,
                List.of("DGX Spark price risk"),
                0.90d,
                0.82d,
                0.60d,
                "HIGH",
                "gpu",
                List.of("NVIDIA"),
                List.of());

        assertEquals("HIGH", result.resourceTier());
        assertTrue(result.riskAdjustedConfidence() < result.confidenceScore());
        assertTrue(result.rewriteTemperature() <= 0.25d);
        assertTrue(result.searchRangeMultiplier() > 1.0d);
        assertTrue(result.getDynamicThreshold() >= 0.24d);
    }

    @Test
    void lowValueQueryKeepsCostSaverTemperatureAndNarrowRange() {
        QueryAnalysisResult result = new QueryAnalysisResult(
                "작은 중고 물건 판매 문구",
                QueryAnalysisResult.QueryIntent.GENERAL,
                List.of(),
                List.of(),
                false,
                false,
                List.of("중고 판매 문구"),
                0.60d,
                0.18d,
                0.20d,
                "LOW",
                null,
                List.of(),
                List.of());

        assertEquals("LOW", result.resourceTier());
        assertTrue(result.rewriteTemperature() >= 0.45d);
        assertTrue(result.searchRangeMultiplier() < 1.0d);
        assertTrue(result.getDynamicThreshold() <= 0.28d);
    }
}
