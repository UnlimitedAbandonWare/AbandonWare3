package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.trace.TraceSnapshotStore;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTraceSnapshotPointerPersisterTest {

    @Test
    void capturesTraceHtmlAndAppendsSafeSnapshotPointer() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(store.captureCustom(eq("chat.trace_html.final"), eq("POST"), eq("/api/chat"), eq(null), eq(null), any(), eq("<html>trace</html>")))
                .thenReturn("snap-1");
        when(history.appendMessageReturningId(7L, "system", "?TRACESNAP?snap-1")).thenReturn(77L);

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L,
                "chat.trace_html.final",
                "POST",
                "/api/chat",
                Map.of("existing", "value"),
                "<html>trace</html>",
                store,
                history,
                LoggerFactory.getLogger(ChatTraceSnapshotPointerPersisterTest.class));

        assertEquals(77L, turnId);
        verify(history).appendMessageReturningId(7L, "system", "?TRACESNAP?snap-1");
    }

    @Test
    void skipsInvalidSnapshotIdsWithoutAppendingMessage() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(store.captureCustom(eq("reason"), eq("GET"), eq("/api/chat"), eq(null), eq(null), any(), eq("<html>trace</html>")))
                .thenReturn("../bad");

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L,
                "reason",
                "GET",
                "/api/chat",
                null,
                "<html>trace</html>",
                store,
                history,
                LoggerFactory.getLogger(ChatTraceSnapshotPointerPersisterTest.class));

        assertNull(turnId);
        verify(history, never()).appendMessageReturningId(any(), any(), any());
    }

    @Test
    void snapshotCaptureFailureLeavesTraceBreadcrumbWithoutRawValues() {
        TraceStore.clear();
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        String raw = "ownerToken=secret-trace";
        when(store.captureCustom(eq("reason"), eq("POST"), eq("/api/chat"), eq(null), eq(null), any(), eq("<html>trace</html>")))
                .thenThrow(new IllegalStateException(raw));

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L,
                "reason",
                "POST",
                "/api/chat",
                null,
                "<html>trace</html>",
                store,
                history,
                LoggerFactory.getLogger(ChatTraceSnapshotPointerPersisterTest.class));

        assertNull(turnId);
        assertEquals(Boolean.TRUE, TraceStore.get("chat.traceSnapshotPointer.suppressed.persist"));
        assertEquals("IllegalStateException",
                TraceStore.get("chat.traceSnapshotPointer.suppressed.persist.errorType"));
        assertEquals("persist", TraceStore.get("chat.traceSnapshotPointer.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("chat.traceSnapshotPointer.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
        verify(history, never()).appendMessageReturningId(any(), any(), any());
        TraceStore.clear();
    }
}
