package com.example.lms.search.policy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchPolicyEngineRedactionTest {

    @Test
    void enrichMetaStoresReasonAsLowCardinalityLabel() {
        SearchPolicyDecision decision = new SearchPolicyDecision(
                SearchPolicyMode.RECALL,
                true,
                true,
                8,
                2,
                1,
                4,
                2,
                1.0d,
                1.0d,
                "private policy reason for query=student medical record");

        Map<String, Object> meta = new SearchPolicyEngine().enrichMeta(new HashMap<>(), decision);
        String reason = String.valueOf(meta.get("searchPolicy.reason"));

        assertFalse(reason.contains("student medical record"), reason);
        assertFalse(reason.contains("query="), reason);
        assertTrue(reason.startsWith("hash:"), reason);
    }

    @Test
    void modeParserOnlyCatchesIllegalArgumentException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/search/policy/SearchPolicyEngine.java"));
        String parserCall = "return SearchPolicyMode.valueOf(s.toUpperCase(Locale.ROOT));";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "search policy mode parser should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 220));
        assertFalse(window.contains("catch (Exception"),
                "search policy mode parser must not swallow every Exception");
        assertFalse(window.contains("catch (Throwable"),
                "search policy mode parser must not swallow Throwable");
        assertTrue(window.contains("catch (IllegalArgumentException"),
                "search policy mode parser should only catch IllegalArgumentException");
    }

    @Test
    void modeParserFallbackLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/search/policy/SearchPolicyEngine.java"));

        assertTrue(source.contains("private static void traceSuppressed(String stage, Throwable failure)"));
        assertTrue(source.contains("traceSuppressed(\"mode.parse\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"searchPolicy.suppressed.\" + safeStage, true);"));
    }
}
