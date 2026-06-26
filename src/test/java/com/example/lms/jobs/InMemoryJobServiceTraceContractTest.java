package com.example.lms.jobs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryJobServiceTraceContractTest {

    @Test
    void workerFailureFallbackLeavesRedactedTraceBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/jobs/InMemoryJobService.java"));

        assertTrue(source.contains("traceSuppressed(\"executeAsync\", t)"),
                "worker failure fallback should leave a redacted breadcrumb");
        assertTrue(source.contains("TraceStore.put(\"jobs.inMemory.suppressed.\" + safeStage, true)"),
                "in-memory job fallback should use the jobs TraceStore namespace");
    }
}
