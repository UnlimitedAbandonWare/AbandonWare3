package com.example.lms.api;

import com.example.lms.service.ChatService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.chat.ChatRunRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatApiControllerCancelTest {

    @Test
    void cancelAcceptsSessionIdFromJsonBody() {
        ChatService chatService = mock(ChatService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(chatService, runRegistry);

        controller.cancel(null, Map.of("sessionId", 42), null);

        verify(chatService).cancelSession(42L);
        verify(runRegistry).markCancelled(42L);
    }

    @Test
    void cancelQueryParamWinsOverJsonBody() {
        ChatService chatService = mock(ChatService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(chatService, runRegistry);

        controller.cancel(7L, Map.of("sessionId", 42), null);

        verify(chatService).cancelSession(7L);
        verify(runRegistry).markCancelled(7L);
    }

    private static ChatApiController controller(ChatService chatService, ChatRunRegistry runRegistry) {
        return new ChatApiController(
                mock(ChatHistoryService.class),
                chatService,
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
                null);
    }
}
