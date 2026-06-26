package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallFailSoftBreadcrumbContractTest {

    @Test
    void reportSnapshotNumericFallbacksUseHashOnlyDebug() throws Exception {
        String source = read("main/java/com/example/lms/service/agent/ReportSnapshotService.java");

        assertTrue(source.contains("[ReportSnapshot] numeric int fallback errorHash={} errorLength={}"));
        assertTrue(source.contains("[ReportSnapshot] numeric double fallback errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(ignore))"));
        assertFalse(source.contains("ignore.getMessage()"));
    }

    @Test
    void answerAndChatFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String answer = read("main/java/com/example/lms/service/answer/AnswerExpanderService.java");
        String summary = read("main/java/com/example/lms/service/chat/JpaChatHistorySummaryService.java");
        String understand = read("main/java/com/example/lms/service/chat/interceptor/UnderstandAndMemorizeInterceptor.java");

        assertTrue(answer.contains("[AnswerExpander] expansion failed errorHash={} errorLength={}"));
        assertTrue(answer.contains("SafeRedactor.hashValue(String.valueOf(e))"));
        assertTrue(summary.contains("log.debug(\"[JpaChatHistorySummary] fail-soft stage={}\", \"repo.findRecent\")"));
        assertTrue(understand.contains("[Understand] meta persistence failed. errorHash={} errorLength={}"));
        assertTrue(understand.contains("[Understand] session id parse failed. errorHash={} errorLength={}"));
        assertFalse(understand.contains("ignore.getMessage()"));
    }

    @Test
    void hyperparameterDiagnosticsAndDisambiguationFallbacksLeaveBreadcrumbs() throws Exception {
        String hyperparameter = read("main/java/com/example/lms/service/config/HyperparameterService.java");
        String diagnostics = read("main/java/com/example/lms/service/diagnostic/DiagnosticsDumpService.java");
        String disambiguation = read("main/java/com/example/lms/service/disambiguation/QueryDisambiguationService.java");
        String disambiguationHelper = read("main/java/com/example/lms/service/disambiguation/DisambiguationTraceSuppressions.java");

        assertTrue(hyperparameter.contains("[Hyperparameter] environment double fallback errorHash={} errorLength={}"));
        assertTrue(diagnostics.contains("[DiagnosticsDump] retrieval diagnostics unavailable errorHash={} errorLength={}"));
        assertTrue(disambiguationHelper.contains("[Disambig] suppression trace failed stage={} errorHash={} errorLength={}"));
        assertDisambiguationStage(disambiguation, "noiseGate.compression");
        assertDisambiguationStage(disambiguation, "auxBlocked.trace");
        assertDisambiguationStage(disambiguation, "noiseEscape.trace");
        assertDisambiguationStage(disambiguation, "noiseEscape.auxOverride");
        assertDisambiguationStage(disambiguation, "auxBlockCheck.outer");
        assertDisambiguationStage(disambiguation, "breakerOpen.auxDownSoft");
        assertDisambiguationStage(disambiguation, "breakerOpen.trace");
        assertDisambiguationStage(disambiguation, "breakerOpen.queryHash");
        assertDisambiguationStage(disambiguation, "cooldown.trace");
        assertDisambiguationStage(disambiguation, "cooldown.debugEvent");
        assertDisambiguationStage(disambiguation, "cooldown.outer");
        assertDisambiguationStage(disambiguation, "breakerOpen.exceptionSoft");
        assertDisambiguationStage(disambiguation, "breakerOpen.exceptionTrace");
        assertDisambiguationStage(disambiguation, "breakerOpen.debugEvent");
        assertDisambiguationStage(disambiguation, "blank.debugEvent");
        assertDisambiguationStage(disambiguation, "blank.auxDownSoft");
        assertDisambiguationStage(disambiguation, "exception.auxDownSoft");
        assertDisambiguationStage(disambiguation, "breakerOpen.exception");
        assertDisambiguationStage(disambiguation, "fallbackAllRounder.noiseClip");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void assertDisambiguationStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[Disambig] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing Disambiguation debug stage: " + stage);
    }
}
