package com.example.lms.guard;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyGuardTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void legacyCitationGateStoresCountOnlyDecisionTrace() {
        CitationGate gate = new CitationGate(2, true);

        assertFalse(gate.check(List.of("private source"), List.of()));

        assertEquals("insufficient_sources", TraceStore.get("guard.legacyCitation.reason"));
        assertEquals(1, TraceStore.get("guard.legacyCitation.sourceCount"));
        assertEquals(0, TraceStore.get("guard.legacyCitation.officialCount"));
        assertEquals(2, TraceStore.get("guard.legacyCitation.requiredCount"));
        assertEquals(1, TraceStore.get("gate.citation.count"));
        assertEquals(Boolean.FALSE, TraceStore.get("gate.citation.passed"));
        assertEquals(Boolean.FALSE, TraceStore.get("gate.hypernova.override"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private source"));
    }

    @Test
    void piiSanitizerStoresLengthOnlyTraceWithoutRawPii() {
        PiiSanitizer sanitizer = new PiiSanitizer(true, "redact");
        String raw = "Call 555-121-9090 or mail user@example.com";

        String sanitized = sanitizer.apply(raw);

        assertTrue(sanitized.contains("***@***"));
        assertEquals(Boolean.TRUE, TraceStore.get("guard.pii.changed"));
        assertEquals(raw.length(), TraceStore.get("guard.pii.inputLength"));
        assertEquals(sanitized.length(), TraceStore.get("guard.pii.outputLength"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("555-121-9090"), trace);
        assertFalse(trace.contains("user@example.com"), trace);
    }
}
