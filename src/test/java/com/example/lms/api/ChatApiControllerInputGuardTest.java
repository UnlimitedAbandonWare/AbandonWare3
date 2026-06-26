package com.example.lms.api;

import com.example.lms.dto.ChatResponseDto;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatApiControllerInputGuardTest {

    @Test
    void chatSyncRejectsNullPayloadBeforeCallingServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);

        var response = controller.chatSync(null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ChatResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("bad_request", body.getContent());
        verifyNoInteractions(historyService, chatService, settingsService, ownerKeyResolver);
    }

    @Test
    void chatRejectsNullPayloadBeforeCallingServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);

        var response = controller.chat(null, null, new MockHttpServletRequest()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ChatResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("bad_request", body.getContent());
        verifyNoInteractions(historyService, chatService, settingsService, ownerKeyResolver);
    }

    @Test
    void chatStreamRejectsNullPayloadWithSingleErrorEventBeforeCallingServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);

        var events = controller.chatStream(null, false, false, null, new MockHttpServletRequest())
                .collectList()
                .block();

        assertNotNull(events);
        assertEquals(1, events.size());
        ChatStreamEvent body = events.get(0).data();
        assertNotNull(body);
        assertEquals("error", body.type());
        assertEquals("bad_request", body.data());
        verifyNoInteractions(historyService, chatService, settingsService, ownerKeyResolver);
    }

    @Test
    void chatRejectsForeignGuestSessionBeforeCallingChatServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ChatSession foreign = new ChatSession("foreign", "owner-a", "ANON");
        foreign.setId(7L);

        when(ownerKeyResolver.ownerKey()).thenReturn("owner-b");
        when(historyService.getSessionWithMessages(7L)).thenReturn(foreign);

        var response = controller.chat(
                        ChatRequestDto.builder().message("hello").sessionId(7L).build(),
                        null,
                        new MockHttpServletRequest())
                .block();

        assertNotNull(response);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        ChatResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("session_forbidden", body.getContent());
        verifyNoInteractions(chatService);
    }

    @Test
    void chatSyncRejectsForeignGuestSessionBeforeCallingChatServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ChatSession foreign = new ChatSession("foreign", "owner-a", "ANON");
        foreign.setId(8L);

        when(ownerKeyResolver.ownerKey()).thenReturn("owner-b");
        when(historyService.getSessionWithMessages(8L)).thenReturn(foreign);

        var response = controller.chatSync(
                ChatRequestDto.builder().message("hello").sessionId(8L).build(),
                null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("session_forbidden", response.getBody().getContent());
        verifyNoInteractions(chatService);
    }

    @Test
    void chatStreamRejectsForeignGuestSessionBeforeCallingChatServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ChatSession foreign = new ChatSession("foreign", "owner-a", "ANON");
        foreign.setId(9L);

        when(ownerKeyResolver.ownerKey()).thenReturn("owner-b");
        when(historyService.getSessionWithMessages(9L)).thenReturn(foreign);

        var events = controller.chatStream(
                        ChatRequestDto.builder().message("hello").sessionId(9L).build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block();

        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("error", events.get(0).data().type());
        assertEquals("session_forbidden", events.get(0).data().data());
        verifyNoInteractions(chatService);
    }

    @Test
    void chatStreamEmitsNonBlankTokenAndFinalEventWhenChatServiceReturnsAnswer() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry);
        ChatSession session = new ChatSession("Harmony SSE smoke", "owner-a", "ANON");
        session.setId(12L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(runRegistry.startOrGet(12L))
                .thenReturn(Sinks.many().replay().limit(32));
        when(historyService.appendMessageReturningId(12L, "assistant", "Harmony answer is ready."))
                .thenReturn(55L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("Harmony answer is ready.", "mock-model", false));

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("Harmony SSE smoke")
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e ->
                e.data() != null
                        && "token".equals(e.data().type())
                        && e.data().data() != null
                        && !e.data().data().isBlank()));
        assertTrue(events.stream().anyMatch(e ->
                e.data() != null
                        && "final".equals(e.data().type())));
    }

    @Test
    void chatStreamEmitsDefaultModelWaitStatusBeforeAnswerTokens() throws Exception {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry);
        ChatSession session = new ChatSession("slow default model", "owner-a", "ANON");
        session.setId(13L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(runRegistry.startOrGet(13L))
                .thenReturn(Sinks.many().replay().limit(32));
        when(historyService.appendMessageReturningId(13L, "assistant", "Delayed answer arrived."))
                .thenReturn(56L);
        CountDownLatch serviceEntered = new CountDownLatch(1);
        CountDownLatch waitStatusSeen = new CountDownLatch(1);
        CountDownLatch releaseAnswer = new CountDownLatch(1);
        CountDownLatch finalSeen = new CountDownLatch(1);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenAnswer(invocation -> {
                    serviceEntered.countDown();
                    if (!releaseAnswer.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release delayed answer");
                    }
                    return ChatResult.of("Delayed answer arrived.", "gemma4:26b", false);
                });

        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> events = new CopyOnWriteArrayList<>();
        var subscription = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("slow default model")
                                .model("gemma4:26b")
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .subscribe(event -> {
                    events.add(event);
                    ChatStreamEvent data = event.data();
                    if (data != null
                            && data.statusSignal() != null
                            && "waiting_for_default_model".equals(data.statusSignal().code())) {
                        waitStatusSeen.countDown();
                    }
                    if (data != null && "final".equals(data.type())) {
                        finalSeen.countDown();
                    }
                });

        assertTrue(serviceEntered.await(1, TimeUnit.SECONDS), "chat service should be running in background");
        assertTrue(waitStatusSeen.await(1, TimeUnit.SECONDS),
                "stream should tell the user that the default model is still running while the service is pending");
        int waitStatus = indexOf(events, "status", "waiting_for_default_model");
        int firstToken = indexOf(events, "token", null);
        assertTrue(waitStatus >= 0);
        assertEquals(-1, firstToken, "answer tokens should not be emitted before the delayed answer arrives");

        releaseAnswer.countDown();
        assertTrue(finalSeen.await(3, TimeUnit.SECONDS), "final event should arrive after delayed answer");
        subscription.dispose();
        firstToken = indexOf(events, "token", null);
        assertTrue(firstToken > waitStatus, "wait status should arrive before answer tokens");
    }

    private static int indexOf(List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> events,
                               String type,
                               String code) {
        for (int i = 0; i < events.size(); i++) {
            ChatStreamEvent event = events.get(i).data();
            if (event == null || !type.equals(event.type())) {
                continue;
            }
            if (code == null) {
                return i;
            }
            ChatStreamEvent.StatusSignal signal = event.statusSignal();
            if (signal != null && code.equals(signal.code())) {
                return i;
            }
        }
        return -1;
    }

    private static ChatApiController controller(
            ChatHistoryService historyService,
            ChatService chatService,
            SettingsService settingsService,
            ClientOwnerKeyResolver ownerKeyResolver) {
        return controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                mock(ChatRunRegistry.class));
    }

    private static ChatApiController controller(
            ChatHistoryService historyService,
            ChatService chatService,
            SettingsService settingsService,
            ClientOwnerKeyResolver ownerKeyResolver,
            ChatRunRegistry runRegistry) {
        return new ChatApiController(
                historyService,
                chatService,
                null,
                settingsService,
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
