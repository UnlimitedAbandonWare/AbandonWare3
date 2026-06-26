package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatApiControllerStateSecurityTest {

    @Test
    void stateRejectsForeignGuestSessionEvenWhenDebugRequested() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, mock(ChatRunRegistry.class), ownerKeyResolver);
        ChatSession session = new ChatSession("foreign", "owner-key", "ANON");
        session.setId(7L);

        when(ownerKeyResolver.ownerKey()).thenReturn("other-key");
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);

        var response = controller.state(7L, true, null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNull(response.getBody().get("traceHtml"));
    }

    private static ChatApiController controller(
            ChatHistoryService historyService,
            ChatRunRegistry runRegistry,
            ClientOwnerKeyResolver ownerKeyResolver) {
        return new ChatApiController(
                historyService,
                mock(ChatService.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                runRegistry,
                ownerKeyResolver);
    }
}
