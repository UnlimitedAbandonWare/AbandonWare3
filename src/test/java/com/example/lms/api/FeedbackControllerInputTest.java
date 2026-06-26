package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.FeedbackDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeedbackControllerInputTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void nullFeedbackBodyReturnsStableBadRequestBeforeServiceCall() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        FeedbackController controller = controller(memoryService, mock(ChatHistoryService.class), mock(ClientOwnerKeyResolver.class));

        var response = controller.feedback(null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("missing_feedback", response.getBody());
        verifyNoInteractions(memoryService);
    }

    @Test
    void foreignGuestSessionFeedbackIsRejectedBeforeMemoryWrite() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver);
        ChatSession session = new ChatSession("foreign", "owner-a", "ANON");
        session.setId(7L);

        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-b");

        var response = controller.feedback(new FeedbackDto(7L, "assistant message", "NEGATIVE", "corrected"));

        assertEquals(403, response.getStatusCode().value());
        assertEquals("session_forbidden", response.getBody());
        assertEquals(Boolean.TRUE, TraceStore.get("api.feedback.rejected"));
        assertEquals("session_forbidden", TraceStore.get("api.feedback.skipped.reason"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue("7"), TraceStore.get("api.feedback.sessionHash"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("assistant message"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("corrected"));
        verify(historyService).getSessionWithMessages(7L);
        verifyNoInteractions(memoryService);
    }

    private static FeedbackController controller(
            MemoryReinforcementService memoryService,
            ChatHistoryService historyService,
            ClientOwnerKeyResolver ownerKeyResolver) {
        return new FeedbackController(memoryService, historyService, ownerKeyResolver);
    }
}
