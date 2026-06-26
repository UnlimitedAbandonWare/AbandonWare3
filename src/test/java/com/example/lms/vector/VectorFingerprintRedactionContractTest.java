package com.example.lms.vector;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorFingerprintRedactionContractTest {

    @Test
    void fingerprintDiagnosticsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/vector/FingerprintAwareEmbeddingStore.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"vector.fp.want\", want);"));
        assertFalse(source.contains("want='{}'"));
        assertFalse(source.contains("dominant fp='{}'"));
        assertFalse(source.contains("gotSample='{}'"));
        assertTrue(source.contains("TraceStore.put(\"vector.fp.wantHash\", SafeRedactor.hashValue(want));"));
        assertTrue(source.contains("wantHash={} wantLength={}"));
        assertTrue(source.contains("dominantFpHash={} dominantFpLength={}"));
        assertTrue(source.contains("gotSampleHash={} gotSampleLength={}"));
        assertTrue(source.contains("[AWX2AF2][vector][fingerprint] metadata read skipped errorType={}"));
        assertTrue(source.contains("[AWX2AF2][vector][fingerprint] fallback count metadata read skipped"));
        assertTrue(source.contains("[AWX2AF2][vector][fingerprint] dominant metadata read skipped"));
        assertTrue(source.contains("[AWX2AF2][vector][fingerprint] segment text read skipped"));
        assertTrue(source.contains("[AWX2AF2][vector][fingerprint] stamp metadata read skipped"));
    }
}
