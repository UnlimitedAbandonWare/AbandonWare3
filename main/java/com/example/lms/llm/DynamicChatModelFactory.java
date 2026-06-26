package com.example.lms.llm;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.guard.KeyResolver;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicChatModelFactory {

    @Value("${llm.chat-model:${llm.fast.model:gemma4:26b}}")
    private String defaultModelName;

    /**
     * Remote(OpenAI/OpenAI-compatible) endpoint.
     *
     * NOTE:
     * - 설정 문서(application-llm.yaml) 키: llm.base-url-openai
     * - 일부 레거시 경로 키: llm.openai.base-url
     * - 운영 환경 키: OPENAI_BASE_URL
     */
    @Value("${llm.base-url-openai:${llm.openai.base-url:${OPENAI_BASE_URL:https://api.openai.com/v1}}}")
    private String openAiBaseUrl;

    /**
     * Local(OpenAI-compatible) endpoint (Ollama/vLLM/llama.cpp).
     *
     * NOTE:
     * - 설정 키: llm.base-url
     * - 일부 레거시 키: llm.ollama.base-url
     */
    @Value("${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}")
    private String localBaseUrl;

    @Value("${llm.fast.base-url:${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}}")
    private String fastLocalBaseUrl;

    @Value("${llm.high.base-url:${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}}")
    private String highLocalBaseUrl;

    @Value("${llm.judge.base-url:${llm.high.base-url:${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}}}")
    private String judgeLocalBaseUrl;

    @Value("${llm.coder.base-url:${llm.high.base-url:${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}}}")
    private String coderLocalBaseUrl;

    @Value("${llm.vision.base-url:${llm.fast.base-url:${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}}}")
    private String visionLocalBaseUrl;

    /**
     * Local API key (optional; some gateways require it). Default is a harmless
     * placeholder.
     */
    @Value("${llm.api-key:${LLM_API_KEY:ollama}}")
    private String localApiKey;

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

    @Value("${llm.dynamic.max-retries:${llm.max-retries:0}}")
    private int dynamicMaxRetries;

    @Value("${llm.ollama-native.think-false.enabled:true}")
    private boolean ollamaNativeThinkFalseEnabled;

    private final Environment env;
    private final KeyResolver keyResolver;

    /**
     * Backward-compatible overload (no penalties).
     */
    public ChatModel lc(String modelName, Double temperature, Double topP, Integer maxTokens) {
        return lc(modelName, temperature, topP, null, null, maxTokens);
    }

    /**
     * Creates a request-scoped ChatModel with optional sampling controls.
     *
     * <p>
     * LangChain4j OpenAiChatModel is configured at build-time (not per request),
     * so this factory is expected to be called frequently.
     * </p>
     */
    public ChatModel lc(String modelName,
            Double temperature,
            Double topP,
            Double frequencyPenalty,
            Double presencePenalty,
            Integer maxTokens) {

        return lcWithTimeout(modelName, temperature, topP, frequencyPenalty, presencePenalty, maxTokens, 120);
    }

    /**
     * Backward-compatible overload (no penalties).
     */
    public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP, Integer maxTokens,
            int timeoutSeconds) {
        return lcWithTimeout(modelName, temperature, topP, null, null, maxTokens, timeoutSeconds);
    }

    /**
     * Router/Workflow 사전 게이트:
     * - OpenAI 계열(gpt-/o*) 요청인데 OpenAI 키가 없으면 false
     */
    public boolean canServe(String modelName) {
        String model = ModelCapabilities.canonicalModelName(modelName);
        if (isLocalModel(model)) {
            return true;
        }
        String key = resolveOpenAiApiKey();
        return key != null && !key.trim().isEmpty();
    }

    public boolean canServeQuietly(String modelName) {
        try {
            return canServe(modelName);
        } catch (RuntimeException ex) {
            log.warn("[AWX2AF2][model-policy] canServe failed modelHash={} errorHash={} errorLength={}",
                    SafeRedactor.hashValue(modelName),
                    SafeRedactor.hashValue(String.valueOf(ex)),
                    String.valueOf(ex).length());
            return false;
        }
    }

    public ChatModel lcWithTimeout(String modelName,
            Double temperature,
            Double topP,
            Double frequencyPenalty,
            Double presencePenalty,
            Integer maxTokens,
            int timeoutSeconds) {

        String rawModel = trimToNull(modelName);
        if (rawModel == null) {
            rawModel = trimToNull(defaultModelName);
        }
        // Extra fallback: a blank llm.fast.model can slip in via env overrides. In that case,
        // fall back to the primary chat model to avoid sending requests with an empty model.
        if (rawModel == null) {
            rawModel = trimToNull(env.getProperty("llm.chat-model"));
        }

        String effectiveModel = ModelCapabilities.canonicalModelName(rawModel);
        if (effectiveModel == null || effectiveModel.isBlank()) {
            throw new IllegalStateException(
                    "model is required (blank modelName after canonicalize). rawModel='"
                            + (rawModel == null ? "<null>" : rawModel) + "'");
        }

        // Route selection + credential selection (fail-soft):
        // - local 모델이면 localBaseUrl/localApiKey
        // - OpenAI 모델이면 openAiBaseUrl + OpenAI key(없으면 IllegalStateException)
        boolean local = isLocalModel(effectiveModel);
        String baseUrl = OpenAiCompatBaseUrl.sanitize(local ? selectLocalBaseUrl(effectiveModel) : openAiBaseUrl);
        String apiKeyForCall = local ? localApiKey : resolveOpenAiApiKey();

        // Best-effort trace breadcrumbs (no secrets).
        try {
            com.example.lms.search.TraceStore.put("llm.factory.model.rawHash", SafeRedactor.hashValue(rawModel));
            com.example.lms.search.TraceStore.put("llm.factory.model.rawLength", rawModel == null ? 0 : rawModel.length());
            com.example.lms.search.TraceStore.put("llm.factory.model.effectiveHash", SafeRedactor.hashValue(effectiveModel));
            com.example.lms.search.TraceStore.put("llm.factory.model.effectiveLength", effectiveModel == null ? 0 : effectiveModel.length());
            com.example.lms.search.TraceStore.put("llm.factory.local", local);
            com.example.lms.search.TraceStore.put("llm.factory.baseUrlHost",
                    LocalLlmGatewaySecurity.endpointHost(baseUrl));
            com.example.lms.search.TraceStore.put("llm.factory.baseUrlHash", SafeRedactor.hashValue(baseUrl));
            com.example.lms.search.TraceStore.put("llm.factory.hasOwnerToken",
                    local && LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken));
        } catch (Throwable ignore) {
            log.debug("DynamicChatModelFactory: trace breadcrumbs skipped errorType={}",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
        }
        if (local) {
            LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                    baseUrl,
                    allowPrivateRemote,
                    allowedHosts,
                    requireAuthForRemote,
                    localApiKey,
                    ownerToken);
        }
        if (!local) {
            assertOpenAiReady(effectiveModel, baseUrl, apiKeyForCall);
        }

        Double safeTemp = null;
        if (temperature != null) {
            double sanitized = ModelCapabilities.sanitizeTemperature(effectiveModel, temperature);
            safeTemp = sanitized;
            if (!Objects.equals(temperature, safeTemp)) {
                log.debug("Adjusted temperature {} -> {} for modelHash={} modelLength={}", temperature, safeTemp, SafeRedactor.hashValue(effectiveModel), effectiveModel == null ? 0 : effectiveModel.length());
            }
        }

        Double safeTopP = null;
        if (topP != null) {
            double sanitized = ModelCapabilities.sanitizeTopP(effectiveModel, topP);
            safeTopP = sanitized;
            if (!Objects.equals(topP, safeTopP)) {
                log.debug("Adjusted top_p {} -> {} for modelHash={} modelLength={}", topP, safeTopP, SafeRedactor.hashValue(effectiveModel), effectiveModel == null ? 0 : effectiveModel.length());
            }
        }

        Double safeFreqPenalty = null;
        if (frequencyPenalty != null) {
            double sanitized = ModelCapabilities.sanitizeFrequencyPenalty(effectiveModel, frequencyPenalty);
            safeFreqPenalty = sanitized;
            if (!Objects.equals(frequencyPenalty, safeFreqPenalty)) {
                log.debug("Adjusted frequency_penalty {} -> {} for modelHash={} modelLength={}", frequencyPenalty, safeFreqPenalty, SafeRedactor.hashValue(effectiveModel), effectiveModel == null ? 0 : effectiveModel.length());
            }
        }

        Double safePresencePenalty = null;
        if (presencePenalty != null) {
            double sanitized = ModelCapabilities.sanitizePresencePenalty(effectiveModel, presencePenalty);
            safePresencePenalty = sanitized;
            if (!Objects.equals(presencePenalty, safePresencePenalty)) {
                log.debug("Adjusted presence_penalty {} -> {} for modelHash={} modelLength={}", presencePenalty, safePresencePenalty, SafeRedactor.hashValue(effectiveModel), effectiveModel == null ? 0 : effectiveModel.length());
            }
        }

        try {
            String safeApiKey = local
                    ? localApiKeyForCall(apiKeyForCall)
                    : apiKeyForCall.trim();
            if (local && shouldUseOllamaNativeThinkFalse(effectiveModel, baseUrl)) {
                com.example.lms.search.TraceStore.put("llm.ollamaNative.route", true);
                com.example.lms.search.TraceStore.put("llm.ollamaNative.route.modelHash", SafeRedactor.hashValue(effectiveModel));
                com.example.lms.search.TraceStore.put("llm.ollamaNative.route.modelLength", effectiveModel.length());
                return new OllamaNativeChatModel(
                        baseUrl,
                        effectiveModel,
                        Duration.ofSeconds(timeoutSeconds),
                        maxTokens,
                        safeTemp);
            }
            var builder = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(safeApiKey)
                    .modelName(effectiveModel)
                    .timeout(Duration.ofSeconds(timeoutSeconds));

            if (local && LocalLlmGatewaySecurity.shouldAttachOwnerToken(baseUrl, allowedHosts)) {
                Map<String, String> headers = LocalLlmGatewaySecurity.ownerTokenHeaders(ownerTokenHeader, ownerToken);
                if (!headers.isEmpty()) {
                    builder.customHeaders(headers);
                }
            }

            // Prevent nested retries/timeouts; LangChain4j 1.0.1 exposes maxRetries(Integer).
            builder.maxRetries(Integer.valueOf(Math.max(0, dynamicMaxRetries)));

            if (safeTemp != null) {
                builder.temperature(safeTemp);
            }
            if (safeTopP != null) {
                builder.topP(safeTopP);
            }

            if (safeFreqPenalty != null) {
                builder.frequencyPenalty(safeFreqPenalty);
            }
            if (safePresencePenalty != null) {
                builder.presencePenalty(safePresencePenalty);
            }

            if (maxTokens != null) {
                if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(effectiveModel, baseUrl)) {
                    builder.maxTokens(maxTokens);
                } else {
                    builder.maxCompletionTokens(maxTokens);
                }
            }

            // Safety: ensure modelName is not dropped by later builder mutations (e.g., maxTokens/maxCompletionTokens)
            builder.modelName(effectiveModel);

            return builder.build();
        } catch (Exception e) {
            throw wrapConnect(e, baseUrl);
        }
    }

    private String localApiKeyForCall(String apiKeyForCall) {
        return ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKeyForCall)
                ? "ollama"
                : apiKeyForCall.trim();
    }

    private String resolveOpenAiApiKey() {
        // UAW strict policy: if multiple sources are set (even if equal), fail-fast.
        // See KeyResolver.resolveOpenAiApiKeyStrict().
        return keyResolver.resolveOpenAiApiKeyStrict();
    }

    private void assertOpenAiReady(String model, String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "OpenAI API key is missing. Requested model='" + model + "', baseUrl='" + baseUrl
                            + "'. Configure 'llm.api-key-openai' (or OPENAI_API_KEY).");
        }

        // Only treat gsk_ as invalid when the target is the real OpenAI endpoint.
        boolean baseLooksOpenAi = baseUrl != null
                && baseUrl.toLowerCase(Locale.ROOT).contains("api.openai.com");

        if (baseLooksOpenAi && apiKey.startsWith("gsk_")) {
            throw new IllegalStateException(
                    "Groq key(gsk_) mapped to OpenAI base-url. Set OPENAI_API_KEY(sk-/sk-proj-) instead.");
        }
    }

    private boolean isLocalModel(String model) {
        if (model == null || model.isBlank())
            return true;

        String m = model.toLowerCase(Locale.ROOT);

        // ":"(예: gemma3:27b), ollama/local 계열은 local로 취급
        if (m.contains(":"))
            return true;
        if (m.contains("ollama") || m.contains("llama") || m.contains("qwen") || m.contains("gemma")
                || m.contains("phi")) {
            return true;
        }

        // gpt-/o* 는 OpenAI로 취급
        if (m.startsWith("gpt-"))
            return false;
        if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4"))
            return false;

        // 기본값: local (fail-soft)
        return true;
    }

    private String selectLocalBaseUrl(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);

        if (m.contains("qwen3-coder")) {
            return firstNonBlank(coderLocalBaseUrl, highLocalBaseUrl, localBaseUrl);
        }
        if (m.contains("qwen3:30b")) {
            return firstNonBlank(judgeLocalBaseUrl, highLocalBaseUrl, localBaseUrl);
        }
        if (m.contains("qwen3-vl")) {
            return firstNonBlank(visionLocalBaseUrl, fastLocalBaseUrl, localBaseUrl);
        }
        if (m.contains("qwen3:8b") || m.contains("qwen2.5")) {
            return firstNonBlank(fastLocalBaseUrl, localBaseUrl);
        }
        if (m.contains("gemma4") || m.contains("gemma3")) {
            return firstNonBlank(highLocalBaseUrl, localBaseUrl);
        }
        return firstNonBlank(localBaseUrl, highLocalBaseUrl, fastLocalBaseUrl);
    }

    private boolean shouldUseOllamaNativeThinkFalse(String model, String baseUrl) {
        if (!ollamaNativeThinkFalseEnabled) {
            return false;
        }
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (!(m.contains("qwen3:") || m.contains("qwen3-vl"))) {
            return false;
        }
        String url = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        return url.contains("127.0.0.1:11434") || url.contains("localhost:11434")
                || url.contains("127.0.0.1:11435") || url.contains("localhost:11435");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trimToNull(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private RuntimeException wrapConnect(Exception e, String baseUrl) {
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException("Failed to build chat model baseUrlHost="
                + LocalLlmGatewaySecurity.endpointHost(baseUrl)
                + " baseUrlHash=" + SafeRedactor.hashValue(baseUrl)
                + " error=" + SafeRedactor.traceLabelOrFallback(e.getMessage(), ""), e);
    }
}
