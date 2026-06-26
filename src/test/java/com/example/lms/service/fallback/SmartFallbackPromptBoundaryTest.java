package com.example.lms.service.fallback;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartFallbackPromptBoundaryTest {

    @Test
    void fallbackLlmCallUsesPromptBuilderBoundary() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/fallback/SmartFallbackService.java"));

        assertTrue(source.contains("private final PromptBuilder promptBuilder;"));
        assertTrue(source.contains("PromptContext.builder()"));
        assertTrue(source.contains("promptBuilder.build(ctx)"));
        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String fallbackSuggestionPrompt = promptBuilder.build(ctx);"));
        assertTrue(source.contains("UserMessage.from(fallbackSuggestionPrompt)"));
        assertFalse(source.contains("String prompt = system + \"\\n\" + user;"));
        assertFalse(source.contains("UserMessage.from(system +"));
    }

    @Test
    void fallbackFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String service = Files.readString(Path.of("main/java/com/example/lms/service/fallback/SmartFallbackService.java"));
        String heuristics = Files.readString(Path.of("main/java/com/example/lms/service/fallback/FallbackHeuristics.java"));

        assertTrue(service.contains("log.debug(\"[SmartFallback] fail-soft stage={}\", \"knowledgeGap.logEvent\")"));
        assertTrue(heuristics.contains("LOG.log(System.Logger.Level.DEBUG, \"[FallbackHeuristics] fail-soft stage={0}\", \"domain.classify\")"));
    }
}
