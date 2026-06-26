package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class TimedChatModelCaller {

    private static final AtomicLong THREAD_IDS = new AtomicLong();

    private TimedChatModelCaller() {
    }

    public static AiMessage chat(
            ChatModel model,
            List<ChatMessage> messages,
            Duration timeout,
            String stage,
            String modelId) throws Exception {
        if (model == null) {
            throw new IllegalStateException("ChatModel is not configured");
        }
        long timeoutMs = normalizeTimeoutMs(timeout);
        ExecutorService executor = Executors.newSingleThreadExecutor(daemonThreadFactory());
        Future<ChatResponse> future = executor.submit(() -> model.chat(messages));
        try {
            ChatResponse response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            AiMessage ai = response == null ? null : response.aiMessage();
            String text = ai == null ? null : ai.text();
            if (text == null || text.isBlank()) {
                traceBlank(stage, modelId, text, response);
                throw new RuntimeException("LLM blank response");
            }
            return ai;
        } catch (TimeoutException timeoutException) {
            future.cancel(false);
            traceTimeout(timeoutMs, stage, modelId);
            TimeoutException failure = new TimeoutException("LLM call timed out");
            failure.initCause(timeoutException);
            throw failure;
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } finally {
            executor.shutdown();
        }
    }

    private static long normalizeTimeoutMs(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return 1_000L;
        }
        return Math.max(1L, timeout.toMillis());
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "awx-llm-hard-timeout-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void traceTimeout(long timeoutMs, String stage, String modelId) {
        try {
            TraceStore.put("llm.call.timeout", true);
            TraceStore.put("llm.call.timeout.stage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
            TraceStore.put("llm.call.timeout.ms", timeoutMs);
            TraceStore.put("llm.call.timeout.modelHash", SafeRedactor.hashValue(modelId));
            TraceStore.put("llm.call.timeout.cancelInterrupt", false);
        } catch (Exception ignored) {
            TraceStore.put("llm.call.timeout.suppressed", true);
        }
    }

    private static void traceBlank(String stage, String modelId, String text, ChatResponse response) {
        try {
            String finishReason = finishReason(response);
            int outputChars = text == null ? 0 : text.length();
            TraceStore.put("llm.call.blank", true);
            TraceStore.put("llm.output.blank", true);
            TraceStore.put("llm.call.blank.count", TraceStore.nextSequence("llm.call.blank"));
            TraceStore.put("llm.call.blank.stage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
            TraceStore.put("llm.call.blank.modelHash", SafeRedactor.hashValue(modelId));
            TraceStore.put("llm.call.blank.outputChars", outputChars);
            TraceStore.put("llm.output.contentLength", outputChars);
            TraceStore.put("llm.output.reason", "blank_response");
            TraceStore.put("llm.output.doneReason", finishReason);
            TraceStore.put("llm.upstream.pressure", 1.0d);
        } catch (Exception ignored) {
            TraceStore.put("llm.call.blank.suppressed", true);
        }
    }

    private static String finishReason(ChatResponse response) {
        Object reason = response == null ? null : response.finishReason();
        return SafeRedactor.traceLabelOrFallback(reason == null ? "unknown" : String.valueOf(reason), "unknown");
    }
}
