package com.example.lms.config;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import com.example.lms.llm.OpenAiTokenParamCompat;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.OllamaNativeChatModel;
import com.example.lms.llm.OpenAiCompatBaseUrl;

import com.example.lms.guard.KeyResolver;
import com.example.lms.guard.ModelGuard;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareBreakerProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * LLM configuration.
 *
 * <p>Important:
 * - Keep internal retries low (ideally 0 for fast/utility models) so the orchestration
 *   layer (timeouts/circuit breakers) can make consistent decisions.
 * </p>
 */
@Configuration
@EnableConfigurationProperties(NightmareBreakerProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);
    private static final String LLM_PRIMARY_PROVIDER_KEY = "llm.primary.provider";
    private static final String LLM_PRIMARY_PORT_KEY = "llm.primary.port";
    private static final String LLM_PRIMARY_SUPPRESSED_STAGE_KEY = "llm.primary.suppressed.stage";
    private static final String LLM_PRIMARY_SUPPRESSED_ERROR_TYPE_KEY = "llm.primary.suppressed.errorType";
    private static final String LLM_FAST_PROVIDER_KEY = "llm.fast.provider";
    private static final String LLM_FAST_PORT_KEY = "llm.fast.port";
    private static final String LLM_FAST_SUPPRESSED_STAGE_KEY = "llm.fast.suppressed.stage";
    private static final String LLM_FAST_SUPPRESSED_ERROR_TYPE_KEY = "llm.fast.suppressed.errorType";

    @Value("${llm.owner-token:${LLM_OWNER_TOKEN:}}")
    private String ownerToken;

    @Value("${llm.owner-token-header:${LLM_OWNER_TOKEN_HEADER:X-Owner-Token}}")
    private String ownerTokenHeader;

    @Value("${llm.provider-guard.allow-private-remote:${LLM_PROVIDER_GUARD_ALLOW_PRIVATE_REMOTE:false}}")
    private boolean allowPrivateRemote;

    @Value("${llm.provider-guard.allowed-hosts:${LLM_PROVIDER_GUARD_ALLOWED_HOSTS:}}")
    private String allowedHosts;

    @Value("${llm.provider-guard.require-auth-for-remote:${LLM_PROVIDER_GUARD_REQUIRE_AUTH_FOR_REMOTE:true}}")
    private boolean requireAuthForRemote;

    @Value("${llm.ollama-native.think-false.enabled:true}")
    private boolean ollamaNativeThinkFalseEnabled = true;

    @Bean(name = {"chatModel","redChatModel"})
    @Primary
    public ChatModel chatModel(
            @Value("${llm.base-url}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.chat-model}") String model,
            @Value("${llm.chat.temperature:0.3}") double temperature,
            @Value("${llm.timeout-seconds:12}") long timeoutSeconds,
            // NOTE: keep internal retries fail-fast by default (0).
            // Outer orchestrators / caller-level retry should own the policy to avoid stacked timeouts.
            @Value("${llm.max-retries:0}") int maxRetries
    ) {
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
            return disabledChatModel("chatModel", model);
        }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
        traceLlmEndpoint(
                LLM_PRIMARY_PROVIDER_KEY,
                LLM_PRIMARY_PORT_KEY,
                LLM_PRIMARY_SUPPRESSED_STAGE_KEY,
                LLM_PRIMARY_SUPPRESSED_ERROR_TYPE_KEY,
                sanitizedBaseUrl);
        assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);

        var builder = OpenAiChatModel.builder()
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        applyGatewayHeaders(builder, sanitizedBaseUrl, model);

        builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

    @Bean(name = "miniModel")
    public ChatModel miniModel(
            @Value("${llm.base-url}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.mini.model:${llm.chat-model}}") String model,
            @Value("${llm.mini.temperature:0.2}") double temperature,
            @Value("${llm.mini.timeout-seconds:12}") long timeoutSeconds,
            // NOTE: keep internal retries fail-fast by default (0).
            @Value("${llm.mini.max-retries:0}") int maxRetries
    ) {
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
            return disabledChatModel("miniModel", model);
        }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
        assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);

        var builder = OpenAiChatModel.builder()
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        applyGatewayHeaders(builder, sanitizedBaseUrl, model);

        builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

    /**
     * Fast model for utility tasks (QueryTransformer, disambiguation, etc.).
     *
     * <p>Hard rule: avoid internal retries for the fast model. Upper orchestration already
     * applies time budgets / fallbacks, and internal retries tend to create zombie work
     * under cancellation/timeouts.</p>
     */
    @Bean(name = {"fastChatModel","greenChatModel"})
    public ChatModel fastChatModel(
            @Value("${llm.fast.base-url:${llm.base-url}}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.fast.model:${llm.chat-model}}") String model,
            @Value("${llm.fast.temperature:0.0}") double temperature,
            @Value("${llm.fast.timeout-seconds:5}") long timeoutSeconds,
            @Value("${llm.fast.max-retries:0}") int maxRetries,
            @Value("${llm.fast.max-tokens:256}") Integer maxTokens
    ) {
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
            return disabledChatModel("fastChatModel", model);
        }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
        traceLlmEndpoint(
                LLM_FAST_PROVIDER_KEY,
                LLM_FAST_PORT_KEY,
                LLM_FAST_SUPPRESSED_STAGE_KEY,
                LLM_FAST_SUPPRESSED_ERROR_TYPE_KEY,
                sanitizedBaseUrl);
        assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);
        if (shouldUseOllamaNativeThinkFalse(model, sanitizedBaseUrl)) {
            TraceStore.put("llm.ollamaNative.route", true);
            TraceStore.put("llm.ollamaNative.route.bean", "fastChatModel");
            return new OllamaNativeChatModel(
                    sanitizedBaseUrl,
                    model,
                    Duration.ofSeconds(timeoutSeconds),
                    maxTokens,
                    ModelCapabilities.sanitizeTemperature(model, temperature));
        }

        var builder = OpenAiChatModel.builder()
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        applyGatewayHeaders(builder, sanitizedBaseUrl, model);
        if (maxTokens != null) {
            if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
                builder.maxTokens(maxTokens);
            } else {
                log.info("[OpenAI-Compat] modelHash={} modelLength={} rejects max_tokens; skipping",
                        SafeRedactor.hashValue(model), lengthOf(model));
            }
        }

        builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

@Bean(name = "exploreChatModel")
public ChatModel exploreChatModel(
        @Value("${llm.explore.base-url:${llm.fast.base-url:${llm.base-url}}}") String baseUrl,
        KeyResolver keyResolver,
        @Value("${llm.explore.model:${llm.chat-model}}") String model,
        @Value("${llm.explore.temperature:0.85}") double temperature,
        @Value("${llm.explore.timeout-seconds:6}") long timeoutSeconds,
        @Value("${llm.explore.max-retries:0}") int maxRetries,
        @Value("${llm.explore.max-tokens:512}") Integer maxTokens
) {
    String apiKey = keyResolver.resolveLocalApiKeyStrict();
    if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
        return disabledChatModel("exploreChatModel", model);
    }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

    String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
    assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);

    var builder = OpenAiChatModel.builder()
            .baseUrl(sanitizedBaseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
            .timeout(Duration.ofSeconds(timeoutSeconds));
    applyGatewayHeaders(builder, sanitizedBaseUrl, model);
    if (maxTokens != null) {
            if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
            builder.maxTokens(maxTokens);
        } else {
            log.info("[OpenAI-Compat] modelHash={} modelLength={} rejects max_tokens; skipping",
                    SafeRedactor.hashValue(model), lengthOf(model));
        }
    }

    builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

    // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
    builder.modelName(model);
    return builder.build();
}

@Bean(name = "judgeChatModel")
public ChatModel judgeChatModel(
        @Value("${llm.judge.base-url:${llm.high.base-url:${llm.base-url}}}") String baseUrl,
        KeyResolver keyResolver,
        @Value("${llm.judge.model:${llm.high.model:${llm.chat-model}}}") String model,
        @Value("${llm.judge.timeout-seconds:6}") long timeoutSeconds,
        @Value("${llm.judge.max-retries:0}") int maxRetries,
        @Value("${llm.judge.max-tokens:512}") Integer maxTokens
) {
    String apiKey = keyResolver.resolveLocalApiKeyStrict();
    if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
        return disabledChatModel("judgeChatModel", model);
    }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

    String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
    assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);

    // Deterministic judge: keep temperature at 0 (or the model's fixed default for rigid sampling models).
    var builder = OpenAiChatModel.builder()
            .baseUrl(sanitizedBaseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .temperature(ModelCapabilities.sanitizeTemperature(model, 0.0d))
            .timeout(Duration.ofSeconds(timeoutSeconds));
    applyGatewayHeaders(builder, sanitizedBaseUrl, model);
    if (maxTokens != null) {
        if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
            builder.maxTokens(maxTokens);
        } else {
            log.info("[OpenAI-Compat] modelHash={} modelLength={} rejects max_tokens; skipping",
                    SafeRedactor.hashValue(model), lengthOf(model));
        }
    }

    builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

    // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
    builder.modelName(model);
    return builder.build();
}

    @Bean(name = "highModel")
    public ChatModel highModel(
            @Value("${llm.high.base-url:${llm.base-url}}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.high.model:${llm.chat-model}}") String model,
            @Value("${llm.high.temperature:0.3}") double temperature,
            @Value("${llm.high.timeout-seconds:30}") long timeoutSeconds,
            // NOTE: keep internal retries fail-fast by default (0).
            @Value("${llm.high.max-retries:0}") int maxRetries,
            @Value("${llm.high.max-tokens:1024}") Integer maxTokens
    ) {
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
            return disabledChatModel("highModel", model);
        }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
        assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);

        var builder = OpenAiChatModel.builder()
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        applyGatewayHeaders(builder, sanitizedBaseUrl, model);
        if (maxTokens != null) {
            if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
                builder.maxTokens(maxTokens);
            } else {
                log.info("[OpenAI-Compat] modelHash={} modelLength={} rejects max_tokens; skipping",
                        SafeRedactor.hashValue(model), lengthOf(model));
            }
        }

        builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

    /**
     * Lightweight LLM-specific circuit breaker used by utility LLM calls
     * (QueryTransformer, query analysis, etc.).
     */
    @Bean
    public NightmareBreaker nightmareBreaker(NightmareBreakerProperties props) {
        return new NightmareBreaker(props);
    }

    private void assertGatewayAllowed(String model, String baseUrl, String apiKey) {
        if (!ModelCapabilities.isLocalChatModelId(model)) {
            return;
        }
        LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                baseUrl,
                allowPrivateRemote,
                allowedHosts,
                requireAuthForRemote,
                apiKey,
                ownerToken);
    }

    private void applyGatewayHeaders(OpenAiChatModel.OpenAiChatModelBuilder builder, String baseUrl, String model) {
        if (builder == null
                || !ModelCapabilities.isLocalChatModelId(model)
                || !LocalLlmGatewaySecurity.shouldAttachOwnerToken(baseUrl, allowedHosts)) {
            return;
        }
        Map<String, String> headers = LocalLlmGatewaySecurity.ownerTokenHeaders(ownerTokenHeader, ownerToken);
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }
    }

    private ChatModel disabledChatModel(String beanName, String model) {
        log.warn("[AWX][runtime-config] status=warning property=llm.api-key reason=provider_disabled_missing_key bean={} modelHash={} modelLength={}",
                beanName, SafeRedactor.hashValue(model), lengthOf(model));
        return new ExpectedFailureChatModel("LLM provider is disabled because api key is not configured.", beanName);
    }

    private static void traceLlmEndpoint(
            String providerKey,
            String portKey,
            String suppressedStageKey,
            String suppressedErrorTypeKey,
            String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            TraceStore.put(providerKey,
                    LocalLlmGatewaySecurity.isKnownExternalProviderBaseUrl(baseUrl) ? "remote" : "local");
            TraceStore.put(portKey, uri.getPort());
        } catch (RuntimeException ex) {
            TraceStore.put(providerKey, "unknown");
            TraceStore.put(portKey, -1);
            TraceStore.put(suppressedStageKey, "parseEndpoint");
            TraceStore.put(suppressedErrorTypeKey, "invalid_url");
        }
    }

    private static int lengthOf(String model) {
        return model == null ? 0 : model.length();
    }

    private boolean shouldUseOllamaNativeThinkFalse(String model, String baseUrl) {
        if (!ollamaNativeThinkFalseEnabled) {
            return false;
        }
        String m = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        if (!(m.contains("qwen3:") || m.contains("qwen3-vl"))) {
            return false;
        }
        String url = baseUrl == null ? "" : baseUrl.toLowerCase(java.util.Locale.ROOT);
        return url.contains("127.0.0.1:11434") || url.contains("localhost:11434")
                || url.contains("127.0.0.1:11435") || url.contains("localhost:11435");
    }

    /**
     * Backward compatible alias.
     */
    @Bean(name = "localChatModel")
    public ChatModel localChatModel(@Qualifier("miniModel") ChatModel delegate) {
        return delegate;
    }
}
