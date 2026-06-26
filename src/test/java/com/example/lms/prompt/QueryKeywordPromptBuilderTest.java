package com.example.lms.prompt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryKeywordPromptBuilderTest {

    private final QueryKeywordPromptBuilder builder = new QueryKeywordPromptBuilder();

    @Test
    void rewritePromptsIncludeHonestRewriteContract() {
        assertContract(builder.buildCorrectionPrompt("그런 뜻 아니다"));
        assertContract(builder.buildKeywordVariantsPrompt("DGX 빚 장비 조언", "DGX", 3));
        assertContract(builder.buildSubQueriesPrompt("발화자와 정정 맥락 분리"));
        assertContract(builder.buildSelectedTermsJsonPrompt("션이 해석했고 준우가 정정했다", "general", 4));
    }

    @Test
    void needleProbePromptTemplatesStayInKeywordPromptBuilder() throws Exception {
        String transformer = Files.readString(
                Path.of("main/java/com/example/lms/transform/QueryTransformer.java"),
                StandardCharsets.UTF_8);
        String promptBuilder = Files.readString(
                Path.of("main/java/com/example/lms/prompt/QueryKeywordPromptBuilder.java"),
                StandardCharsets.UTF_8);

        assertFalse(transformer.contains("You are an assistant that suggests a small list of high-authority websites"));
        assertFalse(transformer.contains("You are generating *needle probe* web search queries."));
        assertTrue(promptBuilder.contains("buildAuthoritySitesPrompt("));
        assertTrue(promptBuilder.contains("buildNeedleProbeQueriesPrompt("));
    }

    @Test
    void queryTransformerBuilderPromptsAvoidGenericPromptRiskCallsites() throws Exception {
        String transformer = Files.readString(
                Path.of("main/java/com/example/lms/transform/QueryTransformer.java"),
                StandardCharsets.UTF_8);
        String needlePlanner = Files.readString(
                Path.of("main/java/com/example/lms/transform/QueryTransformerNeedlePlanner.java"),
                StandardCharsets.UTF_8);

        assertFalse(transformer.contains("String prompt ="));
        assertFalse(transformer.contains("UserMessage.from(prompt)"));
        assertTrue(transformer.contains("QUERY_KEYWORD_PROMPT_BUILDER.buildCorrectionPrompt("));
        assertTrue(needlePlanner.contains("promptBuilder.buildNeedleProbeQueriesPrompt("));
    }

    private static void assertContract(String prompt) {
        assertTrue(prompt.contains("HONEST_REWRITE_CONTRACT"));
        assertTrue(prompt.contains("Preserve speaker attribution"));
        assertTrue(prompt.contains("Do not upgrade ambiguous distress"));
        assertTrue(prompt.contains("interpretation and a correction"));
    }
}
