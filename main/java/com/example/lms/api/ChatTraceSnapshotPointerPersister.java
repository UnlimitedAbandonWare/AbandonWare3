package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

final class ChatTraceSnapshotPointerPersister {

    private static final String TRACE_SNAPSHOT_META_PREFIX = "?TRACESNAP?";

    private ChatTraceSnapshotPointerPersister() {
    }

    static Long persist(
            Long sessionId,
            String reason,
            String method,
            String path,
            Map<String, Object> traceMeta,
            String traceHtml,
            TraceSnapshotStore traceSnapshotStore,
            ChatHistoryService historyService,
            Logger log) {
        if (sessionId == null || traceSnapshotStore == null || traceHtml == null || traceHtml.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> snapMeta = new LinkedHashMap<>(traceMeta == null ? Map.of() : traceMeta);
            snapMeta.putIfAbsent("ui.traceHtml.kind", "splitPanel");
            snapMeta.putIfAbsent("ui.traceHtml.length", traceHtml.length());
            String snapshotId = traceSnapshotStore.captureCustom(reason, method, path, null, null, snapMeta, traceHtml);
            if (!ChatTraceMetaMessageRestorer.isSafeTraceSnapshotId(snapshotId)) {
                return null;
            }
            return historyService.appendMessageReturningId(
                    sessionId,
                    "system",
                    TRACE_SNAPSHOT_META_PREFIX + snapshotId);
        } catch (Exception e) {
            String safeErrorType = errorType(e);
            TraceStore.put("chat.traceSnapshotPointer.suppressed.stage", "persist");
            TraceStore.put("chat.traceSnapshotPointer.suppressed.errorType", safeErrorType);
            TraceStore.put("chat.traceSnapshotPointer.suppressed.persist", true);
            TraceStore.put("chat.traceSnapshotPointer.suppressed.persist.errorType", safeErrorType);
            log.debug("[AWX][trace] snapshot pointer skipped reason={} errorType={}",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"), safeErrorType);
            return null;
        }
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
