package com.example.lms.api;

import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Optional;

final class ChatTraceMetaMessageRestorer {

    private static final Logger log = LoggerFactory.getLogger(ChatTraceMetaMessageRestorer.class);
    private static final String TRACE_META_PREFIX = "?TRACE?";
    private static final String TRACE_META_PREFIX_B64 = "?TRACE64?";
    private static final String TRACE_SNAPSHOT_META_PREFIX = "?TRACESNAP?";
    private static final int MAX_TRACE_META_B64_CHARS = 64_000;

    private ChatTraceMetaMessageRestorer() {
    }

    static Optional<ChatApiController.MessageDto> restore(
            Long turnId,
            String content,
            LocalDateTime timestamp,
            boolean exposeTrace) {
        if (content == null || !exposeTrace) {
            return Optional.empty();
        }
        if (content.startsWith(TRACE_SNAPSHOT_META_PREFIX)) {
            String snapshotId = content.substring(TRACE_SNAPSHOT_META_PREFIX.length()).trim();
            if (!isSafeTraceSnapshotId(snapshotId)) {
                log.debug("[AWX][trace] trace snapshot pointer skipped reason=invalid_id messageId={}", turnId);
                return Optional.empty();
            }
            return Optional.of(new ChatApiController.MessageDto(turnId, "system", traceSnapshotCard(snapshotId), timestamp));
        }
        if (content.startsWith(TRACE_META_PREFIX)) {
            String html = content.substring(TRACE_META_PREFIX.length()).trim();
            return Optional.of(new ChatApiController.MessageDto(turnId, "system",
                    "traceHtml=" + SafeRedactor.diagnosticText("traceHtml", html, 12000), timestamp));
        }
        if (!content.startsWith(TRACE_META_PREFIX_B64)) {
            return Optional.empty();
        }

        String b64 = content.substring(TRACE_META_PREFIX_B64.length()).trim();
        if (b64.length() > MAX_TRACE_META_B64_CHARS) {
            log.warn("[AWX][trace] Trace64 payload skipped reason=too_large messageId={} chars={}", turnId, b64.length());
            return Optional.empty();
        }
        try {
            String html = new String(java.util.Base64.getDecoder().decode(b64),
                    java.nio.charset.StandardCharsets.UTF_8);
            return Optional.of(new ChatApiController.MessageDto(turnId, "system",
                    "traceHtml=" + SafeRedactor.diagnosticText("traceHtml", html, 12000), timestamp));
        } catch (IllegalArgumentException e) {
            log.debug("[AWX][trace] Trace64 payload skipped reason=invalid_base64 messageId={}", turnId);
            return Optional.empty();
        }
    }

    static boolean isSafeTraceSnapshotId(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank() || snapshotId.length() > 160) {
            return false;
        }
        for (int i = 0; i < snapshotId.length(); i++) {
            char ch = snapshotId.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_' || ch == '.' || ch == ':';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String traceSnapshotCard(String snapshotId) {
        String safeId = escapeHtmlAttr(snapshotId);
        String hrefId = java.net.URLEncoder.encode(snapshotId, java.nio.charset.StandardCharsets.UTF_8);
        return "<div class=\"search-trace trace-snapshot-card\" data-trace-snapshot-id=\"" + safeId + "\">"
                + "<strong>Trace snapshot</strong>"
                + "<a href=\"/api/diagnostics/trace/snapshots/" + hrefId
                + "/html\" target=\"_blank\" rel=\"noopener noreferrer\">Open trace snapshot</a>"
                + "</div>";
    }

    private static String escapeHtmlAttr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
