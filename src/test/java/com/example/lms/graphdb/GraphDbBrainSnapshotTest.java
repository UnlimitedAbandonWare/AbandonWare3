package com.example.lms.graphdb;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GraphDbBrainSnapshotTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void malformedReturnedCountUsesStableReasonCodeWithoutRawValue() {
        TraceStore.clear();
        GraphDbBrainSnapshot snapshot = GraphDbBrainSnapshot.fromSummary(Map.of(
                "returnedCount", "private graphdb count"));

        assertEquals(0, snapshot.candidateCount());
        assertEquals("graphDb.snapshot.intValue", TraceStore.get("graphdb.snapshot.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("graphdb.snapshot.suppressed.errorType"));
        assertEquals("invalid_number",
                TraceStore.get("graphdb.snapshot.suppressed.graphDb.snapshot.intValue.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private graphdb count"));
    }
}
