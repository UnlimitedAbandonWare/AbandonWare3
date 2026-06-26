package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class SelfAskTimeoutTrace {
    private SelfAskTimeoutTrace() {
    }

    static void recordCancelSuppressed(String stage, long timeoutMs, String keyword, boolean interruptCleaned) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.inc("selfask.timeout.cancelSuppressed.count");
        TraceStore.put("selfask.timeout.stage", safeStage);
        TraceStore.put("selfask.timeout.errorType", safeStage);
        TraceStore.put("selfask.timeout.cancelSuppressed", true);
        TraceStore.put("selfask.timeout.cancelSuppressed.errorType", safeStage);
        TraceStore.put("selfask.timeout.cancelInterrupt", false);
        TraceStore.put("selfask.timeout.interruptCleaned", interruptCleaned);
        TraceStore.put("selfask.timeout.timeoutMs", Math.max(0L, timeoutMs));
        TraceStore.put("selfask.timeout.queryHash12", SafeRedactor.hash12(keyword));
    }
}
