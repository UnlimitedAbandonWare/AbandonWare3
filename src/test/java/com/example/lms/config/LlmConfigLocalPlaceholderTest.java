package com.example.lms.config;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.OllamaNativeChatModel;
import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConfigLocalPlaceholderTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void loopbackOllamaPlaceholderBuildsChatModels() {
        LlmConfig config = config();
        KeyResolver resolver = new KeyResolver(new MockEnvironment()
                .withProperty("llm.api-key", "ollama"));

        ChatModel chat = config.chatModel(
                "http://localhost:11434/v1",
                resolver,
                ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL,
                0.3d,
                12L,
                0);
        ChatModel fast = config.fastChatModel(
                "http://localhost:11434/v1",
                resolver,
                "qwen3:8b",
                0.0d,
                5L,
                0,
                256);

        assertFalse(chat instanceof ExpectedFailureChatModel);
        assertFalse(fast instanceof ExpectedFailureChatModel);
        assertTrue(fast instanceof OllamaNativeChatModel);
        assertEquals("local", TraceStore.get("llm.primary.provider"));
        assertEquals(11434, TraceStore.get("llm.primary.port"));
        assertEquals("local", TraceStore.get("llm.fast.provider"));
        assertEquals(11434, TraceStore.get("llm.fast.port"));
        assertFalse(TraceStore.getAll().containsValue("http://localhost:11434/v1"));
    }

    @Test
    void remoteLocalGatewayRejectsOllamaPlaceholderWithoutOwnerToken() {
        LlmConfig config = config();
        ReflectionTestUtils.setField(config, "allowPrivateRemote", true);
        ReflectionTestUtils.setField(config, "allowedHosts", "macmini-ollama.internal");
        KeyResolver resolver = new KeyResolver(new MockEnvironment()
                .withProperty("llm.api-key", "ollama"));

        assertThrows(IllegalStateException.class, () -> config.chatModel(
                "https://macmini-ollama.internal/v1",
                resolver,
                ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL,
                0.3d,
                12L,
                0));
    }

    @Test
    void missingKeyWarningDoesNotWriteRawModelIdentifier() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/LlmConfig.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("bean={} model={}"));
        assertTrue(source.contains("bean={} modelHash={} modelLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(model)"));
    }

    @Test
    void maxTokenCompatibilityLogDoesNotWriteRawModelIdentifier() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/LlmConfig.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("model='{}' rejects max_tokens; skipping"));
        assertTrue(source.contains("modelHash={} modelLength={} rejects max_tokens; skipping"));
        assertTrue(source.contains("SafeRedactor.hashValue(model), lengthOf(model)"));
    }

    @Test
    void highModelHonorsConfiguredMaxTokensWithCompatibilityGuard() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/LlmConfig.java"), StandardCharsets.UTF_8);
        int highStart = source.indexOf("public ChatModel highModel(");
        int highEnd = source.indexOf("public NightmareBreaker nightmareBreaker", highStart);
        assertTrue(highStart >= 0);
        assertTrue(highEnd > highStart);
        String highMethod = source.substring(highStart, highEnd);

        assertTrue(highMethod.contains("@Value(\"${llm.high.max-tokens:1024}\") Integer maxTokens"));
        assertTrue(highMethod.contains("OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)"));
        assertTrue(highMethod.contains("builder.maxTokens(maxTokens);"));
        assertTrue(highMethod.indexOf("applyGatewayHeaders(builder, sanitizedBaseUrl, model);")
                < highMethod.indexOf("builder.maxTokens(maxTokens);"));
    }

    @Test
    void applicationLlmYamlDefinesHighMaxTokensDefault() throws Exception {
        String yaml = Files.readString(Path.of("main/resources/application-llm.yaml"), StandardCharsets.UTF_8);
        int highStart = yaml.indexOf("  high:");
        int highEnd = yaml.indexOf("  explore:", highStart);
        String highBlock = yaml.substring(highStart, highEnd);

        assertTrue(highBlock.contains("    max-tokens: ${LLM_HIGH_MAX_TOKENS:1024}"));
    }

    private static LlmConfig config() {
        LlmConfig config = new LlmConfig();
        ReflectionTestUtils.setField(config, "ownerToken", "");
        ReflectionTestUtils.setField(config, "ownerTokenHeader", "X-Owner-Token");
        ReflectionTestUtils.setField(config, "allowPrivateRemote", false);
        ReflectionTestUtils.setField(config, "allowedHosts", "");
        ReflectionTestUtils.setField(config, "requireAuthForRemote", true);
        return config;
    }
}
