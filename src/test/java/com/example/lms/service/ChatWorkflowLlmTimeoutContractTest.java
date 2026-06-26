package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowLlmTimeoutContractTest {

    @Test
    void callWithRetryUsesHardTimeoutWrapperForAllChatModelDraftCalls() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(source.contains("import com.example.lms.llm.TimedChatModelCaller;"));
        assertTrue(method.contains("TimedChatModelCaller.chat("),
                "final draft calls must use the hard timeout wrapper");
        assertFalse(method.contains("modelForCall.chat(msgs).aiMessage()"),
                "primary draft call must not block directly on ChatModel.chat");
        assertFalse(method.contains("healed.chat(msgs).aiMessage()"),
                "self-heal draft calls must not block directly on ChatModel.chat");
    }

    @Test
    void noEvidenceTimeoutFastBailsBeforeAsyncRequestTimeout() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(method.contains("!evidencePresent && timeoutHits >= 1"),
                "zero-evidence chat should fast-bail after the first model timeout");
        assertTrue(method.contains("new LlmFastBailoutException(\"LLM timeout fast-bail\""),
                "fast-bail must be routed through the existing LLM fallback catch path");
    }

    @Test
    void noEvidenceUpstream5xxFastBailsThroughExistingFallbackPath() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(method.contains("\"UPSTREAM_5XX\".equals(cls.code())"),
                "upstream 5xx must be classified explicitly before retry-budget exhaustion");
        assertTrue(method.contains("!evidencePresent && upstream5xxHits >= 1"),
                "zero-evidence chat should not wait through the full retry budget after local upstream 5xx");
        assertTrue(method.contains("new LlmFastBailoutException(\"LLM upstream fast-bail\""),
                "upstream 5xx fast-bail should reuse the existing evidence/local-lite fallback catch path");
        assertTrue(source.contains("TraceStore.put(\"llm.fastBailUpstream5xx\", true)"),
                "top-level fallback catch should leave a low-cardinality upstream fast-bail breadcrumb");
    }

    @Test
    void noEvidenceBlankResponseFastBailsThroughExistingFallbackPath() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(method.contains("\"BLANK_RESPONSE\".equals(cls.code())"),
                "blank local LLM 200 OK responses must be classified before retry-budget exhaustion");
        assertTrue(method.contains("recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, \"blank_response\")"),
                "blank output should block bad local model promotion until a successful response is observed");
        assertTrue(method.contains("!evidencePresent && blankHits >= 1"),
                "zero-evidence chat should not repeatedly call a model that returned blank content");
        assertTrue(method.contains("new LlmFastBailoutException(\"LLM blank response fast-bail\""),
                "blank-response fast-bail should reuse the existing evidence/local-lite fallback catch path");
        assertTrue(source.contains("TraceStore.put(\"llm.fastBailBlankResponse\", true)"),
                "top-level fallback catch should leave a low-cardinality blank-response breadcrumb");
    }

    @Test
    void retryBudgetExceededIsProjectedToTraceBeforeThrowing() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        String traceSource = Files.readString(
                Path.of("main/java/com/example/lms/service/LlmRetryBudgetTrace.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);
        int budgetCheck = method.indexOf("elapsedMs > budgetMs");
        int traceCall = method.indexOf("LlmRetryBudgetTrace.emit(", budgetCheck);
        int throwSite = method.indexOf("throw new RuntimeException(\"LLM retry budget exceeded\"", budgetCheck);

        assertTrue(traceCall > budgetCheck,
                "retry-budget overflow should leave a TraceStore breadcrumb before throwing");
        assertTrue(traceCall < throwSite,
                "retry-budget breadcrumb must be written before the exception leaves callWithRetry");
        assertTrue(traceSource.contains("TraceStore.put(\"llm.retryBudget.exceeded\", true)"));
        assertTrue(traceSource.contains("TraceStore.put(\"llm.retryBudget.code\", safeCode)"));
        assertTrue(traceSource.contains("TraceStore.nextSequence(\"llm.retryBudget.exceeded\")"));
    }

    @Test
    void requestedModelDraftCallsUseSelectionScopedTimeout() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(source.contains("import com.example.lms.llm.RequestedModelTimeoutPolicy;"));
        assertTrue(method.contains("RequestedModelTimeoutPolicy.timeoutSeconds(dto.getModel(), resolved, llmTimeoutSeconds"));
        assertTrue(method.contains("Duration.ofSeconds(callTimeoutSeconds)"));
        assertFalse(method.contains("Duration.ofSeconds(llmTimeoutSeconds),\r\n                        \"chat_draft\""));
        assertFalse(method.contains("Duration.ofSeconds(llmTimeoutSeconds),\n                        \"chat_draft\""));
    }

    @Test
    void llmUnavailableWithNoEvidenceDoesNotDegradeToEvidenceOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int catchBlock = source.indexOf("LlmFastBailoutException fastBail = unwrapFastBail(e);");
        int noEvidenceFallback = source.indexOf("NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModel", catchBlock);
        int evidenceFallback = source.indexOf("composeEvidenceFallback(finalQuery, topDocs, vectorDocs", catchBlock);

        assertTrue(noEvidenceFallback >= 0,
                "LLM unavailable fallback must first detect zero-evidence conversations");
        assertTrue(noEvidenceFallback < evidenceFallback,
                "zero-evidence fallback must run before evidence-only fallback");
        String fallbackSource = Files.readString(
                Path.of("main/java/com/example/lms/service/NoEvidenceChatFallback.java"),
                StandardCharsets.UTF_8);
        assertTrue(fallbackSource.contains("\":fallback:local-lite\""),
                "zero-evidence LLM fallback should not be reported as fallback:evidence");
    }
}
