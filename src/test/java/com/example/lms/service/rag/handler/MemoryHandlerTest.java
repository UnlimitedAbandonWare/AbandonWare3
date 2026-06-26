package com.example.lms.service.rag.handler;

import com.example.lms.service.ChatHistoryService;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryHandlerTest {

    @Test
    void loadForSessionCombinesRollingSummaryAndRecentTurns() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        when(historyService.getConversationMemorySnapshot(42L)).thenReturn(new ChatHistoryService.ConversationMemorySnapshot(
                7L,
                "User prefers Korean explanations.",
                List.of("korean", "session"),
                List.of("User prefers Korean explanations."),
                4,
                3,
                24,
                400,
                120,
                0.30d,
                true,
                "abc123"));
        when(historyService.getFormattedRecentHistory(42L, 4))
                .thenReturn(List.of(
                        "System: (Rolling Summary)\nUser prefers Korean explanations.",
                        "User: 이전 질문",
                        "Assistant: 이전 답변"));

        MemoryHandler handler = new MemoryHandler(historyService);
        ReflectionTestUtils.setField(handler, "maxTurns", 4);

        String memory = handler.loadForSession(42L);

        assertTrue(memory.contains("Conversation summary:"));
        assertTrue(memory.contains("User prefers Korean explanations."));
        assertTrue(memory.contains("Anchor keywords:"));
        assertTrue(memory.contains("korean, session"));
        assertTrue(memory.contains("Important session memory:"));
        assertTrue(memory.contains("Recent turns:"));
        assertTrue(memory.contains("Assistant: 이전 답변"));
        assertFalse(memory.contains("System: (Rolling Summary)"));
    }

    @Test
    void loadForSessionReturnsNullWhenNoMemoryExists() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        when(historyService.getConversationMemorySnapshot(42L))
                .thenReturn(ChatHistoryService.ConversationMemorySnapshot.empty());
        when(historyService.getFormattedRecentHistory(42L, 4)).thenReturn(List.of());

        MemoryHandler handler = new MemoryHandler(historyService);
        ReflectionTestUtils.setField(handler, "maxTurns", 4);

        assertNull(handler.loadForSession(42L));
    }

    @Test
    void loadRecoveryContentsBuildsRedactedSessionAndHistoryContent() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        when(historyService.getConversationMemorySnapshot(42L)).thenReturn(new ChatHistoryService.ConversationMemorySnapshot(
                7L,
                "User asked about recovery loops.",
                List.of("recovery", "rag"),
                List.of("Search recovery should keep previous context."),
                4,
                3,
                24,
                400,
                120,
                0.30d,
                true,
                "abc123"));
        when(historyService.getFormattedRecentHistory(42L, 4))
                .thenReturn(List.of(
                        "System: (Rolling Summary)\nUser asked about recovery loops.",
                        "User: How should zero-result RAG recover?",
                        "Assistant: Use bounded recovery."));

        MemoryHandler handler = new MemoryHandler(historyService);
        ReflectionTestUtils.setField(handler, "maxTurns", 4);

        List<Content> contents = handler.loadRecoveryContents(42L, 4);

        assertEquals(2, contents.size());
        Map<String, Object> sessionMeta = contents.get(0).textSegment().metadata().toMap();
        Map<String, Object> historyMeta = contents.get(1).textSegment().metadata().toMap();
        assertEquals("session_memory", sessionMeta.get("source"));
        assertEquals("conversation_history", historyMeta.get("source"));
        assertEquals("true", sessionMeta.get("_retrieval_recovery"));
        assertTrue(sessionMeta.containsKey("sidHash"));
        assertFalse(String.valueOf(sessionMeta.get("sidHash")).contains("42"));
        assertFalse(contents.get(1).textSegment().text().contains("Rolling Summary"));
    }

    @Test
    void rehydrateReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/handler/MemoryHandler.java"));

        assertFalse(source.contains("TraceStore.put(\"memory.rehydrate.reason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"memory.rehydrate.reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains("TraceStore.put(\"memory.rehydrate.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }
}
