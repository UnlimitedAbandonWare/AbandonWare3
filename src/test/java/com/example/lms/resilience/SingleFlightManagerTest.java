package com.example.lms.resilience;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleFlightManagerTest {

    @Test
    void workerTaskFailureLeavesRedactedBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/resilience/SingleFlightManager.java"));

        assertTrue(source.contains("traceSkipped(\"single_flight_task\""),
                "worker task failure should leave a scanner-visible breadcrumb before completing exceptionally");
        assertTrue(source.contains("[AWX][single-flight] trace skipped"),
                "worker task fallback log should use a redacted stage/errorType message");
        assertTrue(source.contains("f.completeExceptionally(e);"),
                "breadcrumb must not replace exception propagation to the waiting caller");
    }
}
