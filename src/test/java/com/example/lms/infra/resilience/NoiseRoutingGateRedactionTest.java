package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseRoutingGateRedactionTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(NoiseRoutingGate.PROP_ENABLED);
        System.clearProperty(NoiseRoutingGate.PROP_DETERMINISTIC);
        TraceStore.clear();
    }

    @Test
    void arbitraryGateKeyDoesNotBecomeRawTraceKeySegment() {
        System.setProperty(NoiseRoutingGate.PROP_ENABLED, "true");
        System.setProperty(NoiseRoutingGate.PROP_DETERMINISTIC, "true");
        String rawGateKey = "ownertoken " + "sk-" + "12345678901234567890";

        NoiseRoutingGate.decideEscape(rawGateKey, 1.0d, null);

        String rendered = String.valueOf(TraceStore.getAll()).toLowerCase(Locale.ROOT);
        assertFalse(rendered.contains("ownertoken"), rendered);
        assertFalse(rendered.contains("sk-" + "12345678901234567890"), rendered);
        assertTrue(rendered.contains("orch.noisegate.hash_"), rendered);
    }

    @Test
    void stableGateKeyKeepsReadableTraceSegment() {
        System.setProperty(NoiseRoutingGate.PROP_ENABLED, "true");
        System.setProperty(NoiseRoutingGate.PROP_DETERMINISTIC, "true");

        NoiseRoutingGate.decideEscape("qtx.compression", 1.0d, null);

        assertEquals(Boolean.TRUE, TraceStore.get("orch.noiseGate.qtx.compression.escape"));
    }

    @Test
    void failSoftNoiseGatePathsLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/infra/resilience/NoiseRoutingGate.java"));

        assertTrue(source.contains("traceSuppressed(\"noiseGate.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"noiseGate.decide\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"orch.noiseGate.suppressed.\" + safeStage, true);"));
    }
}
