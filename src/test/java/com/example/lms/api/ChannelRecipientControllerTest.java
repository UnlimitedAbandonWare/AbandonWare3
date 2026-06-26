package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChannelRecipientService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChannelRecipientControllerTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void missingAccessTokenLeavesProviderDisabledTraceWithoutRawToken() {
        ChannelRecipientService recipientService = mock(ChannelRecipientService.class);
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("channelAccessToken")).thenReturn(null);
        ChannelRecipientController controller = new ChannelRecipientController(recipientService);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.showRecipients(0, session, "", "", model);

        assertEquals("channels/recipients", view);
        assertEquals(List.of(), model.getAttribute("recipients"));
        assertEquals(1, model.getAttribute("page"));
        assertEquals("channel recipient provider is not configured", model.getAttribute("error"));
        assertEquals(Boolean.TRUE, TraceStore.get("channel.recipients.controller.providerDisabled"));
        assertEquals("missing_access_token", TraceStore.get("channel.recipients.controller.skipped.reason"));
        assertEquals(0, TraceStore.get("channel.recipients.controller.returnedCount"));
        assertFalse(TraceStore.getAll().toString().contains("channelAccessToken"));
        verifyNoInteractions(recipientService);
    }
}
