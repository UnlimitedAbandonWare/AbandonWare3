package com.example.lms.service;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatHistoryServiceImplConversationMemoryTest {

    @Test
    void rollingSummaryPersistsAsSystemMetaAndHistoryFormattingSkipsIt() throws Exception {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatHistoryServiceImpl service = newService(sessionRepository, messageRepository);

        ChatSession session = new ChatSession("memory");
        session.setId(42L);
        ChatMessage user = message(session, 1L, "user", "remember blue");
        ChatMessage assistant = message(session, 2L, "assistant", "blue noted");
        ChatMessage rsum = message(session, 3L, "system",
                "⎔RSUM⎔{\"lastMessageId\":2,\"summary\":\"old summary\",\"turns\":2,\"updatedAt\":\"2026-05-26T00:00:00Z\"}");

        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
        when(messageRepository.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                eq(42L), eq("system"), eq("⎔RSUM⎔"))).thenReturn(Optional.empty());
        when(messageRepository.findBySession_IdOrderByCreatedAtDesc(eq(42L), any(Pageable.class)))
                .thenReturn(List.of(assistant, user));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(42L))
                .thenReturn(List.of(user, assistant, rsum));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateRollingSummary(42L, 2L);

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        org.mockito.Mockito.verify(messageRepository).save(saved.capture());
        assertEquals("system", saved.getValue().getRole());
        String savedContent = saved.getValue().getContent();
        assertTrue(savedContent.startsWith("⎔RSUM⎔"));
        assertTrue(savedContent.contains("\"lastMessageId\":2"));
        assertTrue(savedContent.contains("remember blue"));
        assertTrue(savedContent.contains("blue noted"));

        String storedPayload = savedContent.substring("⎔RSUM⎔".length());
        int newline = storedPayload.indexOf('\n');
        assertTrue(newline > 0);
        String metaJson = storedPayload.substring(0, newline);
        String summaryBody = storedPayload.substring(newline + 1);
        java.util.Map<?, ?> payload = new ObjectMapper().readValue(
                metaJson,
                java.util.Map.class);
        assertEquals(2, ((Number) payload.get("turns")).intValue());
        assertTrue(((List<?>) payload.get("anchors")).contains("blue"));
        assertFalse(((List<?>) payload.get("importantSentences")).isEmpty());
        assertTrue(((Number) payload.get("sentenceCount")).intValue() >= 2);
        assertTrue(((Number) payload.get("tokenEstimate")).intValue() > 0);
        assertTrue(((Number) payload.get("rawCharCount")).intValue() > 0);
        assertEquals(summaryBody.length(), ((Number) payload.get("compressedCharCount")).intValue());
        assertTrue(((Number) payload.get("compressionRatio")).doubleValue() > 0.0d);
        assertEquals(Boolean.TRUE, payload.get("promoted"));
        assertFalse(String.valueOf(payload.get("promotionHash")).isBlank());

        assertEquals(List.of("User: remember blue", "Assistant: blue noted"),
                service.getFormattedRecentHistory(42L, 8));
    }

    @Test
    void getRollingSummaryReadsBareJsonRsumPayload() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatHistoryServiceImpl service = newService(sessionRepository, messageRepository);

        ChatSession session = new ChatSession("memory");
        ChatMessage rsum = message(session, 7L, "system",
                "⎔RSUM⎔{\"lastMessageId\":6,\"summary\":\"persistent summary\",\"turns\":4,\"updatedAt\":\"2026-05-26T00:00:00Z\"}");
        when(messageRepository.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                eq(42L), eq("system"), eq("⎔RSUM⎔"))).thenReturn(Optional.of(rsum));

        assertEquals(Optional.of("persistent summary"), service.getRollingSummary(42L));
        ChatHistoryService.ConversationMemorySnapshot snapshot = service.getConversationMemorySnapshot(42L);
        assertEquals("persistent summary", snapshot.summary());
        assertTrue(snapshot.anchors().isEmpty());
        assertTrue(snapshot.importantSentences().isEmpty());
        assertFalse(snapshot.promoted());
    }

    @Test
    void getRollingSummaryReadsEnvelopeAndPrefersBodySummary() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatHistoryServiceImpl service = newService(sessionRepository, messageRepository);

        ChatSession session = new ChatSession("memory");
        ChatMessage rsum = message(session, 7L, "system",
                "⎔RSUM⎔{\"lastMessageId\":6,\"summary\":\"metadata summary\",\"turns\":4,"
                        + "\"compressedCharCount\":5,\"updatedAt\":\"2026-05-26T00:00:00Z\"}\nbody summary wins");
        when(messageRepository.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                eq(42L), eq("system"), eq("⎔RSUM⎔"))).thenReturn(Optional.of(rsum));

        assertEquals(Optional.of("body summary wins"), service.getRollingSummary(42L));
        ChatHistoryService.ConversationMemorySnapshot snapshot = service.getConversationMemorySnapshot(42L);
        assertEquals("body summary wins", snapshot.summary());
        assertEquals(5, snapshot.compressedCharCount());
    }

    private static ChatHistoryServiceImpl newService(
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository) {
        ChatHistoryServiceImpl service = new ChatHistoryServiceImpl(
                sessionRepository,
                messageRepository,
                mock(AdministratorRepository.class),
                new ObjectMapper(),
                mock(ClientOwnerKeyResolver.class));
        ReflectionTestUtils.setField(service, "rollingSummaryMaxChars", 1200);
        ReflectionTestUtils.setField(service, "rollingSummaryPromoteMinTurns", 2);
        ReflectionTestUtils.setField(service, "rollingSummaryPromoteTokenThreshold", 999);
        ReflectionTestUtils.setField(service, "rollingSummaryPromoteSentenceThreshold", 999);
        ReflectionTestUtils.setField(service, "rollingSummaryAnchorCount", 4);
        ReflectionTestUtils.setField(service, "rollingSummaryImportantSentenceCount", 2);
        return service;
    }

    private static ChatMessage message(ChatSession session, Long id, String role, String content) {
        ChatMessage message = new ChatMessage(session, role, content);
        message.setId(id);
        return message;
    }
}
