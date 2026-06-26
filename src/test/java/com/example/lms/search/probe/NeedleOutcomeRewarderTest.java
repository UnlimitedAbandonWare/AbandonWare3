package com.example.lms.search.probe;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

class NeedleOutcomeRewarderTest {

    @Test
    void rewarderDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/probe/NeedleOutcomeRewarder.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Needle outcome rewarder needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void contributionEvaluatorDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/probe/NeedleContributionEvaluator.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Needle contribution evaluator needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }
}
