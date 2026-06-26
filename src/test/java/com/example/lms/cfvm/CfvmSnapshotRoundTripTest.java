package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfvmSnapshotRoundTripTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void boltzmannWeightsSurviveSnapshotRestore() {
        RawMatrixBuffer source = new RawMatrixBuffer(9, 0.35d);
        source.updateWeight(3, 0.90d);
        source.setBoltzmannTemp(0.42d);

        RawMatrixBuffer restored = new RawMatrixBuffer(9, 0.35d);
        restored.restoreFromSnapshot(source.exportWeights(), source.getBoltzmannTemp());

        assertArrayEquals(source.getWeights(), restored.getWeights(), 1.0e-12d);
        assertEquals(0.42d, restored.getBoltzmannTemp(), 1.0e-12d);
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.rawBuffer.restoredFromSnapshot"));
        assertEquals(0.42d, ((Number) TraceStore.get("cfvm.rawBuffer.boltzmannTemp")).doubleValue(), 1.0e-12d);
        assertTrue(String.valueOf(TraceStore.getAll()).contains("cfvm.rawBuffer.restoredFromSnapshot"));
    }
}
