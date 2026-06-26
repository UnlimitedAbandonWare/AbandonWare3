package com.example.lms.service.llm;

import com.example.lms.infra.resilience.FriendShieldPatternDetector;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Component
public class LangChain4jLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmClient.class);

    /**
     * Utility LLM: keep it fast and fail-soft.
     */
    @Qualifier("fastChatModel")
    private final ChatModel chatModel;

    public LangChain4jLlmClient(@Qualifier("fastChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @Override
    public String complete(String llmCompletionPrompt) {
        return completeWithKey(NightmareKeys.FAST_LLM_COMPLETE, llmCompletionPrompt);
    }

    @Override
    public String completeWithKey(String breakerKey, String llmCompletionPrompt) {
        String key = (breakerKey == null || breakerKey.isBlank())
                ? NightmareKeys.FAST_LLM_COMPLETE
                : breakerKey;

        // ✅ 오케스트레이션 접목: 단계별 key로 브레이커 상태를 공유해야 상위가 제대로 degrade 한다.
        if (nightmareBreaker != null) {
            return nightmareBreaker.execute(
                    key,
                    llmCompletionPrompt,
                    () -> callModel(key, llmCompletionPrompt),
                    FriendShieldPatternDetector::looksLikeSilentFailure,
                    () -> ""
            );
        }

        try {
            return callModel(key, llmCompletionPrompt);
        } catch (Exception e) {
            log.warn("LLM call failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return "";
        }
    }

    private String callModel(String stage, String llmCompletionPrompt) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String modelClass = modelClassLabel();
        String prompt = String.valueOf(llmCompletionPrompt);
        TraceStore.put("llm.client.stage", safeStage);
        TraceStore.put("llm.client.modelClass", modelClass);
        TraceStore.put("llm.client.promptHash", SafeRedactor.hashValue(prompt));
        TraceStore.put("llm.client.promptLength", prompt.length());

        dev.langchain4j.model.chat.response.ChatResponse res;
        try {
            res = chatModel.chat(List.of(UserMessage.from(llmCompletionPrompt)));
        } catch (RuntimeException e) {
            traceFailure(safeStage, modelClass, prompt, e);
            throw e;
        }
        if (res == null || res.aiMessage() == null) {
            traceOutput(safeStage, modelClass, prompt, "");
            return "";
        }
        var ai = res.aiMessage();
        String out = ai.text() == null ? "" : ai.text();
        traceOutput(safeStage, modelClass, prompt, out);
        return out;
    }

    private void traceOutput(String safeStage, String modelClass, String prompt, String out) {
        int outputLength = out == null ? 0 : out.length();
        boolean blank = out == null || out.isBlank();
        TraceStore.put("llm.client.outputLength", outputLength);
        TraceStore.put("llm.client.blank", blank);
        if (blank) {
            log.warn("[AWX][llm-client] blank stage={} modelClass={} promptHash={} promptLength={} outputLength={}",
                    safeStage, modelClass, SafeRedactor.hashValue(prompt), prompt == null ? 0 : prompt.length(), outputLength);
        }
    }

    private void traceFailure(String safeStage, String modelClass, String prompt, RuntimeException e) {
        String errorType = SafeRedactor.traceLabelOrFallback(e == null ? null : e.getClass().getSimpleName(), "unknown");
        String errorMessage = messageOf(e);
        int errorLength = errorMessage == null ? 0 : errorMessage.length();
        TraceStore.put("llm.client.failed", true);
        TraceStore.put("llm.client.errorType", errorType);
        TraceStore.put("llm.client.errorHash", SafeRedactor.hashValue(errorMessage));
        TraceStore.put("llm.client.errorLength", errorLength);
        log.warn("[AWX][llm-client] failed stage={} modelClass={} errorType={} errorHash={} errorLength={} promptHash={} promptLength={}",
                safeStage, modelClass, errorType, SafeRedactor.hashValue(errorMessage), errorLength,
                SafeRedactor.hashValue(prompt), prompt == null ? 0 : prompt.length());
    }

    private String modelClassLabel() {
        String simpleName = chatModel == null ? null : chatModel.getClass().getSimpleName();
        return SafeRedactor.traceLabelOrFallback(simpleName, "unknown");
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
}
