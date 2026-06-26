package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedChatModelCallerTest {

    @BeforeEach
    void clearTraceBefore() {
        TraceStore.clear();
    }

    @AfterEach
    void clearTraceAfter() {
        TraceStore.clear();
    }

    @Test
    void returnsAiMessageWhenModelRespondsWithinTimeout() throws Exception {
        AiMessage message = TimedChatModelCaller.chat(
                new StaticModel("ok"),
                List.of(UserMessage.from("hello")),
                Duration.ofSeconds(1),
                "chat_draft",
                "gemma4:26b");

        assertEquals("ok", message.text());
        assertNull(TraceStore.get("llm.call.timeout"));
    }

    @Test
    void blankAiMessageIsClassifiedAsFailureAndTracedWithoutPromptLeak() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> TimedChatModelCaller.chat(
                new StaticModel("   "),
                List.of(UserMessage.from("private prompt that must not enter trace")),
                Duration.ofSeconds(1),
                "chat_draft",
                "qwen3:8b"));

        assertEquals("LLM blank response", failure.getMessage());
        assertEquals(Boolean.TRUE, TraceStore.get("llm.call.blank"));
        assertEquals("chat_draft", TraceStore.get("llm.call.blank.stage"));
        assertTrue(String.valueOf(TraceStore.get("llm.call.blank.modelHash")).startsWith("hash:"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private prompt"), trace);
        assertFalse(trace.contains("qwen3:8b"), trace);
    }

    @Test
    void timesOutWithoutInterruptingWorkerOrLeakingPromptText() {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        long started = System.currentTimeMillis();
        TimeoutException failure = assertThrows(TimeoutException.class, () -> TimedChatModelCaller.chat(
                new SlowModel(interrupted),
                List.of(UserMessage.from("raw-secret prompt that must not enter trace")),
                Duration.ofMillis(75),
                "chat_draft",
                "gemma4:26b"));

        long elapsed = System.currentTimeMillis() - started;
        assertTrue(elapsed < 1_000L, "hard timeout should return promptly, elapsedMs=" + elapsed);
        assertEquals("LLM call timed out", failure.getMessage());
        assertFalse(interrupted.get(), "timeout cancellation must not interrupt provider worker");

        assertEquals(Boolean.TRUE, TraceStore.get("llm.call.timeout"));
        assertEquals("chat_draft", TraceStore.get("llm.call.timeout.stage"));
        assertEquals(75L, ((Number) TraceStore.get("llm.call.timeout.ms")).longValue());
        assertTrue(String.valueOf(TraceStore.get("llm.call.timeout.modelHash")).startsWith("hash:"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("raw-secret prompt"), trace);
        assertFalse(trace.contains("gemma4:26b"), trace);
    }

    private record StaticModel(String text) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }

    private record SlowModel(AtomicBoolean interrupted) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException ex) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("late"))
                    .build();
        }
    }
}
