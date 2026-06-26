package com.example.lms.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTraceMetaMessageRestorerTest {

    @Test
    void snapshotPointerRestoresOnlySafeIdsWhenTraceIsExposed() {
        assertTrue(ChatTraceMetaMessageRestorer.restore(
                1L,
                "?TRACESNAP?snap_20260612-1433.01",
                LocalDateTime.of(2026, 6, 12, 14, 33),
                false).isEmpty());

        ChatApiController.MessageDto dto = ChatTraceMetaMessageRestorer.restore(
                2L,
                "?TRACESNAP?snap_20260612-1433.01",
                LocalDateTime.of(2026, 6, 12, 14, 34),
                true).orElseThrow();

        assertEquals("system", dto.role());
        assertTrue(dto.content().contains("data-trace-snapshot-id=\"snap_20260612-1433.01\""));
        assertTrue(dto.content().contains("/api/diagnostics/trace/snapshots/snap_20260612-1433.01/html"));
        assertTrue(ChatTraceMetaMessageRestorer.restore(
                3L,
                "?TRACESNAP?../unsafe",
                LocalDateTime.now(),
                true).isEmpty());
    }

    @Test
    void legacyTracePayloadsRestoreAsRedactedSummaries() {
        String rawHtml = "<section>ownerToken=secret-value trace</section>";
        String encoded = Base64.getEncoder().encodeToString(rawHtml.getBytes(StandardCharsets.UTF_8));

        for (String content : java.util.List.of("?TRACE?" + rawHtml, "?TRACE64?" + encoded)) {
            ChatApiController.MessageDto dto = ChatTraceMetaMessageRestorer.restore(
                    4L,
                    content,
                    LocalDateTime.of(2026, 6, 12, 14, 35),
                    true).orElseThrow();

            assertEquals("system", dto.role());
            assertTrue(dto.content().contains("traceHtml"));
            assertFalse(dto.content().contains(rawHtml));
            assertFalse(dto.content().contains("secret-value"));
        }
    }
}
