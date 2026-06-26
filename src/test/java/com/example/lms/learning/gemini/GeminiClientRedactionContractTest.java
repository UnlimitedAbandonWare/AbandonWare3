package com.example.lms.learning.gemini;

import com.example.lms.guard.KeyResolver;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiClientRedactionContractTest {

    @Test
    void translateFailureFallbackRedactsSecretLikeInputText() {
        GeminiClient client = new GeminiClient(WebClient.builder(), new KeyResolver(new MockEnvironment()));
        String rawKey = "sk-" + "1234567890abcdef1234";

        String result = client.translate("translate " + rawKey, "en", "ko").block();

        assertTrue(result.contains("[translation failed]"), result);
        assertTrue(result.contains("translate "), result);
        assertTrue(result.contains("***"), result);
        assertFalse(result.contains(rawKey), result);
        assertFalse(result.contains("1234567890abcdef1234"), result);
    }

    @Test
    void generateFailureFallbackUsesHashAndLengthOnly() {
        GeminiClient client = new GeminiClient(WebClient.builder(), new KeyResolver(new MockEnvironment()));

        String result = client.generate("prompt with ownerToken=raw-secret").block();

        assertTrue(result.contains("\"ok\"   : false"), result);
        assertTrue(result.contains("\"errorHash\""), result);
        assertTrue(result.contains("\"errorLength\""), result);
        assertFalse(result.contains("Gemini API key missing"), result);
        assertFalse(result.contains("ownerToken"), result);
    }

    @Test
    void keywordVariantFailureFallsBackWithTraceHashAndLengthOnly() {
        TraceStore.clear();
        try {
            MockEnvironment env = new MockEnvironment()
                    .withProperty("gemini.api-key", "AIza" + "1234567890abcdef1234567890");
            WebClient.Builder builder = WebClient.builder()
                    .exchangeFunction(request -> Mono.error(new IllegalStateException("ownerToken=raw-secret")));
            GeminiClient client = new GeminiClient(builder, new KeyResolver(env));

            GeminiClient.KeywordVariantsResult result = client.keywordVariantsWithMeta(
                    "prompt with ownerToken=raw-secret",
                    "anchor",
                    2,
                    java.time.Duration.ofMillis(50));

            assertTrue(result.variants().isEmpty());
            assertEquals("keywordVariants", TraceStore.get("gemini.suppressed.stage"));
            assertEquals("IllegalStateException", TraceStore.get("gemini.suppressed.errorType"));
            assertTrue(String.valueOf(TraceStore.get("gemini.suppressed.messageHash")).startsWith("hash:"));
            assertEquals("ownerToken=raw-secret".length(), TraceStore.get("gemini.suppressed.messageLength"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("ownerToken=raw-secret"), trace);
            assertFalse(trace.contains("1234567890abcdef1234567890"), trace);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void generatedPromptCallsitesUseStageSpecificPromptNames() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/learning/gemini/GeminiClient.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt = \"Translate"));
        assertFalse(source.contains("String prompt = \"\"\""));
        assertTrue(source.contains("String translationPrompt = \"Translate"));
        assertTrue(source.contains("return postToGemini(translationPrompt)"));
        assertTrue(source.contains("String keywordVariantPrompt = \"\"\""));
        assertTrue(source.contains("entity = postToGeminiEntity(keywordVariantPrompt)"));
        assertTrue(source.contains("traceKeywordVariantSuppressed(e)"));
    }

    @Test
    void failSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/learning/gemini/GeminiClient.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 240)"));
        assertTrue(source.contains("[Gemini] translate API failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("[Gemini] generate API failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }
}
