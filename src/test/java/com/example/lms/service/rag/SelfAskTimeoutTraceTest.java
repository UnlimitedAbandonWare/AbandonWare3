package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAskTimeoutTraceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void recordCancelSuppressedSanitizesSensitiveStageLabel() {
        String secret = "sk-" + "selfAskTimeoutStageSecret123456789";

        SelfAskTimeoutTrace.recordCancelSuppressed("stage=" + secret, 25L, "query " + secret, true);

        Object stage = TraceStore.get("selfask.timeout.stage");
        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(String.valueOf(stage).startsWith("hash:"));
        assertEquals(stage, TraceStore.get("selfask.timeout.errorType"));
        assertEquals(stage, TraceStore.get("selfask.timeout.cancelSuppressed.errorType"));
        assertFalse(trace.contains(secret));
    }
}
