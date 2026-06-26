package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import ai.abandonware.nova.orch.llm.ModelGuardSupport;
import ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import com.example.lms.guard.KeyResolver;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.router.LlmRouterContext;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.OpenAiTokenParamCompat;
import com.example.lms.llm.gateway.FallbackAwareChatModel;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmGatewayBreadcrumbPublisher;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.RoutingEligibility;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestration glue for UAW's llmrouter.* logical model IDs.
 *
 * <p>
 * Intercepts DynamicChatModelFactory.lcWithTimeout(..) and resolves:
 * - llmrouter.<key> -> llmrouter.models.<key> mapping
 * - llmrouter.auto / llmrouter -> automatic selection via
 * {@link LlmRouterBandit}
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 200)
public class LlmRouterAspect {

    private static final Logger log = LoggerFactory.getLogger(LlmRouterAspect.class);

    private final Environment env;
    private final LlmRouterProperties props;
    private final LlmRouterBandit bandit;

    private final NovaModelGuardProperties modelGuardProps;
    private final ObjectProvider<KeyResolver> keyResolverProvider;
    private final HybridLlmGatewayProbeService gatewayProbeService;
    private final LlmGatewayBreadcrumbPublisher gatewayBreadcrumbPublisher;
    private final LlmGatewayFailureClassifier gatewayFailureClassifier;

    public LlmRouterAspect(Environment env, LlmRouterProperties props, LlmRouterBandit bandit,
            NovaModelGuardProperties modelGuardProps,
            ObjectProvider<KeyResolver> keyResolverProvider) {
        this(env, props, bandit, modelGuardProps, keyResolverProvider, null, null, null);
    }

    public LlmRouterAspect(Environment env, LlmRouterProperties props, LlmRouterBandit bandit,
            NovaModelGuardProperties modelGuardProps,
            ObjectProvider<KeyResolver> keyResolverProvider,
            HybridLlmGatewayProbeService gatewayProbeService,
            LlmGatewayBreadcrumbPublisher gatewayBreadcrumbPublisher,
            LlmGatewayFailureClassifier gatewayFailureClassifier) {
        this.env = env;
        this.props = props;
        this.bandit = bandit;
        this.modelGuardProps = modelGuardProps;
        this.keyResolverProvider = keyResolverProvider;
        this.gatewayProbeService = gatewayProbeService;
        this.gatewayBreadcrumbPublisher = gatewayBreadcrumbPublisher;
        this.gatewayFailureClassifier = gatewayFailureClassifier;
    }

    @Around("execution(* com.example.lms.llm.DynamicChatModelFactory.lcWithTimeout(..))")
    public Object aroundLcWithTimeout(ProceedingJoinPoint pjp) throws Throwable {
        if (props == null || !props.isEnabled() || bandit == null) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();

        // Optional alias mapping for legacy/default model ids.
        boolean argsRewritten = false;
        if (args != null && args.length > 0 && args[0] instanceof String modelId) {
            String aliased = resolveAlias(modelId);
            if (aliased != null && !aliased.isBlank() && !aliased.equals(modelId)) {
                args = args.clone();
                args[0] = aliased;
                argsRewritten = true;
            }
        }

        CallArgs ca = CallArgs.parse(args);
        if (ca == null) {
            return argsRewritten ? pjp.proceed(args) : pjp.proceed();
        }

        // 1) Resolve llmrouter.* directly.
        LlmRouterBandit.Selected sel = bandit.pick(ca.requestedModelId, gatewayFilter(ca));
        if (sel != null) {
            return routeWithGateway(sel, ca);
        }

        // 2) Otherwise proceed; if OpenAI key is missing, optionally fall back to
        // llmrouter.auto.
        try {
            return argsRewritten ? pjp.proceed(args) : pjp.proceed();
        } catch (IllegalStateException ise) {
            if (props.isFallbackWhenOpenAiMissing() && looksLikeMissingOpenAiKey(ise)) {
                LlmRouterBandit.Selected autoSel = bandit.pick("llmrouter.auto", gatewayFilter(ca));
                if (autoSel != null) {
                    log.warn("[llmrouter] OpenAI key missing; falling back to local auto route key={}", autoSel.key());
                    return routeWithGateway(autoSel, ca);
                }
            }
            throw ise;
        }
    }

    private LlmRouterBandit.RouteEligibilityFilter gatewayFilter(CallArgs ca) {
        if (gatewayProbeService == null || !gatewayProbeService.isEnforce()) {
            return LlmRouterBandit.RouteEligibilityFilter.always();
        }
        return (key, cfg) -> {
            if (cfg != null && cfg.isFallbackOnly()) {
                return false;
            }
            RoutingEligibility eligibility = gatewayProbeService.evaluate(key, cfg, stage(ca, cfg));
            if (gatewayBreadcrumbPublisher != null) {
                gatewayBreadcrumbPublisher.publishEligibility(eligibility);
            }
            return eligibility == null || eligibility.eligible();
        };
    }

    private ChatModel routeWithGateway(LlmRouterBandit.Selected sel, CallArgs ca) {
        RoutingEligibility eligibility = null;
        if (gatewayProbeService != null) {
            eligibility = gatewayProbeService.evaluate(sel.key(), sel.cfg(), stage(ca, sel.cfg()));
            if (gatewayBreadcrumbPublisher != null) {
                gatewayBreadcrumbPublisher.publishEligibility(eligibility);
            }
            if (gatewayProbeService.isEnforce() && eligibility != null && !eligibility.eligible()) {
                LlmRouterBandit.Selected fallback = fallbackSelection(sel);
                if (fallback != null) {
                    if (gatewayBreadcrumbPublisher != null) {
                        gatewayBreadcrumbPublisher.publishFallback(sel.key(), fallback.key(), eligibility.primaryFailure(),
                                "enforce_ineligible");
                    }
                    return recordOutcomes(buildRoutedModel(fallback, ca), fallback.key());
                }
                failRoute(sel.key(), sel.cfg() == null ? null : sel.cfg().getBaseUrl(),
                        "gateway_ineligible_" + eligibility.primaryFailure().name().toLowerCase(Locale.ROOT));
            }
        }

        ChatModel primary = recordOutcomes(buildRoutedModel(sel, ca), sel.key());
        LlmRouterBandit.Selected fallback = fallbackSelection(sel);
        if (fallback == null || gatewayFailureClassifier == null) {
            return primary;
        }
        return new FallbackAwareChatModel(primary,
                () -> recordOutcomes(buildRoutedModel(fallback, ca), fallback.key()),
                gatewayFailureClassifier,
                gatewayBreadcrumbPublisher,
                sel.key(),
                fallback.key());
    }

    private ChatModel recordOutcomes(ChatModel model, String key) {
        if (model == null || model instanceof ExpectedFailureChatModel || bandit == null || !StringUtils.hasText(key)) {
            return model;
        }
        if (model instanceof RecordingChatModel) {
            return model;
        }
        return new RecordingChatModel(model, bandit, key, gatewayFailureClassifier);
    }

    private LlmRouterBandit.Selected fallbackSelection(LlmRouterBandit.Selected selected) {
        if (selected == null || props == null || props.getModels() == null
                || gatewayProbeService == null || !gatewayProbeService.cloudFallbackEnabled()) {
            return null;
        }
        String fallbackKey = firstNonBlank(
                selected.cfg() == null ? null : selected.cfg().getFallbackKey(),
                gatewayProbeService.cloudRouteKey());
        if (fallbackKey == null || fallbackKey.equals(selected.key())) {
            return null;
        }
        LlmRouterProperties.ModelConfig fallbackCfg = props.getModels().get(fallbackKey);
        if (fallbackCfg == null || !fallbackCfg.isEnabled()) {
            return null;
        }
        return new LlmRouterBandit.Selected(fallbackKey, fallbackCfg);
    }

    private static String stage(CallArgs ca, LlmRouterProperties.ModelConfig cfg) {
        if (cfg != null && StringUtils.hasText(cfg.getStage())) {
            return cfg.getStage().trim();
        }
        if (ca == null || ca.requestedModelId == null) {
            return "chat";
        }
        String requested = ca.requestedModelId.toLowerCase(Locale.ROOT);
        if (requested.contains("judge") || requested.contains("critic")) {
            return "judge";
        }
        if (requested.contains("coder")) {
            return "coder";
        }
        if (requested.contains("vision")) {
            return "vision";
        }
        return "chat";
    }

    private ChatModel buildRoutedModel(LlmRouterBandit.Selected sel, CallArgs ca) {
        String key = sel.key();
        LlmRouterProperties.ModelConfig cfg = sel.cfg();

        if (cfg == null) {
            failRoute(key, null, "missing_route_config");
        }
        if (!cfg.isEnabled()) {
            failRoute(key, cfg.getBaseUrl(), "route_disabled");
        }

        String modelName = trimToNull(cfg.getName());
        String rawBaseUrl = trimToNull(cfg.getBaseUrl());
        if (modelName == null || rawBaseUrl == null) {
            failRoute(key, rawBaseUrl, "missing_route_config");
        }

        String baseUrl = normalizeBaseUrl(rawBaseUrl);

        // Model-guard: prevent Responses-only models from hitting /v1/chat/completions.
        if (modelGuardProps != null && modelGuardProps.isEnabled()
                && ModelGuardSupport.isResponsesOnlyModel(modelName, modelGuardProps.getResponsesOnlyPrefixes())
                && (!modelGuardProps.isOpenAiBaseOnly() || ModelGuardSupport.looksLikeOpenAiBaseUrl(baseUrl))) {

            try {
                String canonicalModel = ModelGuardSupport.canonicalModelName(modelName);
                TraceStore.put("llm.modelGuard.requestedModelHash", SafeRedactor.hashValue(canonicalModel));
                TraceStore.put("llm.modelGuard.requestedModelLength", canonicalModel == null ? 0 : canonicalModel.length());
                TraceStore.put("llm.modelGuard.mode", modelGuardProps.getMode().name());
            } catch (Exception ignore) {
                traceSuppressed("modelGuard.requestedModel", ignore);
            }

            switch (modelGuardProps.getMode()) {
                case FAIL_FAST:
                    return expectedFailure(
                            modelName,
                            "/v1/chat/completions",
                            "FAIL_FAST",
                            "responses_only_model_on_chat_completions_endpoint");
                case SUBSTITUTE_CHAT:
                    String sub = modelGuardProps.getSubstituteChatModel();
                    if (!StringUtils.hasText(sub)) {
                        sub = get("llm.chat-model");
                    }
                    if (!StringUtils.hasText(sub)) {
                        return expectedFailure(
                                modelName,
                                "/v1/chat/completions",
                                "SUBSTITUTE_CHAT(no_substitute_configured)",
                                "responses_only_model_on_chat_completions_endpoint");
                    }
                    if (StringUtils.hasText(sub)) {
                        modelName = sub.trim();
                        try {
                            TraceStore.put("llm.modelGuard.substituteChatModelHash", SafeRedactor.hashValue(modelName));
                            TraceStore.put("llm.modelGuard.substituteChatModelLength", modelName.length());
                        } catch (Exception ignore) {
                            traceSuppressed("modelGuard.substituteChatModel", ignore);
                        }
                        if (ModelGuardSupport.isResponsesOnlyModel(modelName, modelGuardProps.getResponsesOnlyPrefixes())) {
                            return expectedFailure(
                                    cfg.getName(),
                                    "/v1/chat/completions",
                                    "SUBSTITUTE_CHAT(no_chat_compatible_substitute)",
                                    "responses_only_model_on_chat_completions_endpoint");
                        }
                    }
                    break;
                case ROUTE_RESPONSES:
                    String apiKey = resolveOpenAiApiKey();
                    if (!StringUtils.hasText(apiKey)) {
                        return expectedFailure(
                                modelName,
                                "/v1/responses",
                                "ROUTE_RESPONSES(no_api_key)",
                                "no_api_key_for_responses_endpoint");
                    }
                    return new OpenAiResponsesChatModel(baseUrl, apiKey, modelName, ca.timeoutMs);
            }
        }

        String apiKey = resolveApiKeyForBaseUrl(baseUrl);
        boolean localGatewayRoute = isLocalGatewayRoute(baseUrl, modelName);
        if (localGatewayRoute) {
            LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                    baseUrl,
                    bool("llm.provider-guard.allow-private-remote", "LLM_PROVIDER_GUARD_ALLOW_PRIVATE_REMOTE", false),
                    gatewayAllowedHosts(),
                    bool("llm.provider-guard.require-auth-for-remote", "LLM_PROVIDER_GUARD_REQUIRE_AUTH_FOR_REMOTE", true),
                    apiKey,
                    ownerToken());
        }

        // carry to request end (for success/fail recording)
        LlmRouterContext.set(key, baseUrl, modelName);

        Double temperature = ModelCapabilities.sanitizeTemperature(modelName, ca.temperature);
        Double topP = ModelCapabilities.sanitizeTopP(modelName, ca.topP);
        Double freq = ModelCapabilities.sanitizeFrequencyPenalty(modelName, ca.frequencyPenalty);
        Double pres = ModelCapabilities.sanitizePresencePenalty(modelName, ca.presencePenalty);

        OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(Math.max(1, ca.timeoutSeconds)));

        if (localGatewayRoute && LocalLlmGatewaySecurity.shouldAttachOwnerToken(baseUrl, gatewayAllowedHosts())) {
            Map<String, String> headers = LocalLlmGatewaySecurity.ownerTokenHeaders(
                    ownerTokenHeader(),
                    ownerToken());
            if (!headers.isEmpty()) {
                b.customHeaders(headers);
                try {
                    TraceStore.put("llmrouter.endpointHost", LocalLlmGatewaySecurity.endpointHost(baseUrl));
                    TraceStore.put("llmrouter.hasOwnerToken", true);
                } catch (Exception ignore) {
                    traceSuppressed("ownerToken.trace", ignore);
                }
            }
        }

        if (temperature != null) {
            b.temperature(temperature);
        }
        if (topP != null) {
            b.topP(topP);
        }

        if (freq != null) {
            b.frequencyPenalty(freq);
        }
        if (pres != null) {
            b.presencePenalty(pres);
        }

        if (ca.maxTokens != null && ca.maxTokens > 0
                && OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(modelName, baseUrl)) {
            b.maxTokens(ca.maxTokens);
        }

        // Safety: ensure modelName is not dropped by later builder mutations (e.g.,
        // maxTokens)
        b.modelName(modelName);

        return b.build();
    }

    private ExpectedFailureChatModel expectedFailure(String requestedModel,
                                                     String endpoint,
                                                     String actionTaken,
                                                     String failReason) {
        TraceStore.put("llm.modelGuard.triggered", true);
        TraceStore.put("llm.modelGuard.endpoint", endpoint == null ? "unknown_endpoint" : endpoint);
        TraceStore.put("llm.modelGuard.failReason",
                SafeRedactor.traceLabelOrFallback(failReason, "unknown_model_guard_failure"));
        return new ExpectedFailureChatModel(
                ModelGuardSupport.buildExpectedFailureMessage(requestedModel, endpoint, actionTaken),
                SafeRedactor.hashValue(ModelGuardSupport.canonicalModelName(requestedModel)));
    }

    private String resolveLocalApiKey() {
        // DynamicChatModelFactory's local key default is ollama; replicate that
        // behavior.
        return firstNonMissingLocalKey(get("llm.api-key"), get("LLM_API_KEY"), System.getenv("LLM_API_KEY"), "ollama");
    }

    private String resolveOpenAiApiKey() {
        KeyResolver kr = keyResolverProvider.getIfAvailable();
        if (kr == null) {
            return firstNonBlank(
                    get("llm.api-key-openai"),
                    get("llm.openai.api-key"),
                    get("OPENAI_API_KEY"));
        }
        return kr.getPropertyOrEnvOpenAiKey();
    }

    private String resolveApiKeyForBaseUrl(String baseUrl) {
        String provider = providerForBaseUrl(baseUrl);
        if ("groq".equals(provider)) {
            return requireProviderKey(provider, resolveGroqApiKey(), "missing GROQ_API_KEY", baseUrl);
        }
        if ("cerebras".equals(provider)) {
            return requireProviderKey(provider, resolveCerebrasApiKey(), "missing CEREBRAS_API_KEY", baseUrl);
        }
        if ("openrouter".equals(provider)) {
            return requireProviderKey(provider, resolveOpenRouterApiKey(), "missing OPENROUTER_API_KEY", baseUrl);
        }
        if ("opencode".equals(provider)) {
            return requireProviderKey(provider, resolveOpenCodeApiKey(), "missing OPENCODE_API_KEY", baseUrl);
        }
        if ("openai".equals(provider)) {
            return requireProviderKey(provider, resolveOpenAiApiKey(), "missing OPENAI_API_KEY", baseUrl);
        }
        // Otherwise, use local/generic API key.
        return resolveLocalApiKey();
    }

    private String resolveGroqApiKey() {
        KeyResolver kr = keyResolverProvider.getIfAvailable();
        if (kr == null) {
            return firstNonBlank(get("llm.groq.api-key"), System.getenv("GROQ_API_KEY"));
        }
        return kr.resolveGroqApiKeyStrict();
    }

    private String resolveCerebrasApiKey() {
        KeyResolver kr = keyResolverProvider.getIfAvailable();
        if (kr == null) {
            return firstNonBlank(get("llm.cerebras.api-key"), System.getenv("CEREBRAS_API_KEY"));
        }
        return kr.resolveCerebrasApiKeyStrict();
    }

    private String resolveOpenRouterApiKey() {
        KeyResolver kr = keyResolverProvider.getIfAvailable();
        if (kr == null) {
            return resolveStrictProviderKey(
                    "OpenRouter",
                    "llm.openrouter.api-key",
                    "OPENROUTER_API_KEY");
        }
        return kr.resolveOpenRouterApiKeyStrict();
    }

    private String resolveOpenCodeApiKey() {
        KeyResolver kr = keyResolverProvider.getIfAvailable();
        if (kr == null) {
            return resolveStrictProviderKey(
                    "OpenCode",
                    "llm.opencode.api-key",
                    "OPENCODE_API_KEY");
        }
        return kr.resolveOpenCodeApiKeyStrict();
    }

    private String requireProviderKey(String provider, String apiKey, String disabledReason, String baseUrl) {
        if (StringUtils.hasText(apiKey) && !looksLikePlaceholderKey(apiKey)) {
            return apiKey;
        }
        try {
            TraceStore.put("llmrouter.api.provider", provider);
            TraceStore.put("llmrouter.api.providerDisabled", true);
            TraceStore.put("llmrouter.api.failureClass", "provider-disabled");
            TraceStore.put("llmrouter.api.endpointHost", LocalLlmGatewaySecurity.endpointHost(baseUrl));
            TraceStore.put("llmrouter.api.hasKey", false);
            TraceStore.put("llmrouter.api.hasOwnerToken", false);
            TraceStore.put("llmrouter.api.disabledReason", SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
        } catch (Exception ignore) {
            traceSuppressed("providerDisabled.trace", ignore);
        }
        throw new IllegalStateException("provider=" + provider + " disabledReason="
                + SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
    }

    private void failRoute(String key, String rawBaseUrl, String disabledReason) {
        try {
            String baseUrl = normalizeBaseUrl(rawBaseUrl);
            TraceStore.put("llmrouter.route.key", SafeRedactor.traceLabelOrFallback(key, "route"));
            TraceStore.put("llmrouter.route.enabled", false);
            TraceStore.put("llmrouter.api.provider", providerForBaseUrl(baseUrl));
            TraceStore.put("llmrouter.api.providerDisabled", true);
            TraceStore.put("llmrouter.api.failureClass", "provider-disabled");
            TraceStore.put("llmrouter.api.endpointHost", LocalLlmGatewaySecurity.endpointHost(baseUrl));
            TraceStore.put("llmrouter.api.hasKey", false);
            TraceStore.put("llmrouter.api.hasOwnerToken", false);
            TraceStore.put("llmrouter.api.disabledReason", SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
        } catch (Exception ignore) {
            traceSuppressed("failRoute.trace", ignore);
        }
        throw new IllegalStateException("provider=llmrouter." + SafeRedactor.traceLabelOrFallback(key, "route") + " disabledReason="
                + SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
    }

    private static String providerForBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "local";
        }
        String u = baseUrl.toLowerCase(Locale.ROOT);
        if (u.contains("api.groq.com")) {
            return "groq";
        }
        if (u.contains("api.cerebras.ai")) {
            return "cerebras";
        }
        if (u.contains("openrouter.ai")) {
            return "openrouter";
        }
        if (u.contains("opencode.ai")) {
            return "opencode";
        }
        if (ModelGuardSupport.looksLikeOpenAiBaseUrl(baseUrl)) {
            return "openai";
        }
        return "local";
    }

    private boolean isLocalGatewayRoute(String baseUrl, String modelName) {
        return "local".equals(providerForBaseUrl(baseUrl))
                && ModelCapabilities.isLocalChatModelId(modelName);
    }

    private static boolean looksLikePlaceholderKey(String raw) {
        if (raw == null) {
            return true;
        }
        String k = raw.trim().toLowerCase(Locale.ROOT);
        return k.isEmpty()
                || "dummy".equals(k)
                || "null".equals(k)
                || "test".equals(k)
                || "changeme".equals(k)
                || "sk-local".equals(k)
                || "ollama".equals(k)
                || k.startsWith("${");
    }

    private String get(String key) {
        if (env == null || key == null) {
            return null;
        }
        try {
            return env.getProperty(key);
        } catch (Exception ignore) {
            traceSuppressed("config.get", ignore);
            return null;
        }
    }

    private boolean bool(String propertyKey, String envKey, boolean defaultValue) {
        String value = firstNonBlank(get(propertyKey), get(envKey), System.getenv(envKey));
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private String gatewayAllowedHosts() {
        return firstNonBlank(
                get("llm.provider-guard.allowed-hosts"),
                get("LLM_PROVIDER_GUARD_ALLOWED_HOSTS"),
                System.getenv("LLM_PROVIDER_GUARD_ALLOWED_HOSTS"));
    }

    private String ownerToken() {
        return firstNonBlank(get("llm.owner-token"), get("LLM_OWNER_TOKEN"), System.getenv("LLM_OWNER_TOKEN"));
    }

    private String ownerTokenHeader() {
        return firstNonBlank(get("llm.owner-token-header"), get("LLM_OWNER_TOKEN_HEADER"), "X-Owner-Token");
    }

    private String resolveStrictProviderKey(String label, String propertyKey, String envKey) {
        String propertyValue = trimToNull(get(propertyKey));
        String envValue = trimToNull(get(envKey));
        if (propertyValue != null && envValue != null) {
            throw new IllegalStateException("Conflicting " + label + " API keys: set only ONE of ["
                    + propertyKey + ", " + envKey + "]");
        }
        return firstNonBlank(propertyValue, envValue);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String firstNonMissingLocalKey(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (!ConfigValueGuards.isMissingLocalOpenAiCompatKey(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolveAlias(String requestedModelId) {
        if (requestedModelId == null || props == null) {
            return null;
        }
        var aliases = props.getAliases();
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }

        String key = requestedModelId.trim();
        if (key.isEmpty()) {
            return null;
        }

        String v = aliases.get(key);
        if (v == null) {
            v = aliases.get(key.toLowerCase(Locale.ROOT));
        }
        if (v == null) {
            return null;
        }
        String out = v.trim();
        return out.isEmpty() ? null : out;
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/v1")) {
            return s;
        }
        return s + "/v1";
    }

    private static void traceSuppressed(String stage, Exception ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = SafeRedactor.traceLabelOrFallback(
                ignored == null ? null : ignored.getClass().getSimpleName(), "unknown");
        TraceStore.put("llmrouter.suppressed.stage", safeStage);
        TraceStore.put("llmrouter.suppressed.errorType", errorType);
        TraceStore.put("llmrouter.suppressed." + safeStage, true);
        TraceStore.put("llmrouter.suppressed." + safeStage + ".errorType", errorType);
    }

    private static boolean looksLikeMissingOpenAiKey(IllegalStateException ise) {
        if (ise == null) {
            return false;
        }
        String msg = ise.getMessage();
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase();
        return m.contains("openai") && m.contains("api key") && m.contains("missing");
    }

    static final class RecordingChatModel implements ChatModel {
        private final ChatModel delegate;
        private final LlmRouterBandit bandit;
        private final String key;
        private final LlmGatewayFailureClassifier failureClassifier;

        RecordingChatModel(
                ChatModel delegate,
                LlmRouterBandit bandit,
                String key,
                LlmGatewayFailureClassifier failureClassifier) {
            this.delegate = delegate;
            this.bandit = bandit;
            this.key = key;
            this.failureClassifier = failureClassifier == null
                    ? new LlmGatewayFailureClassifier()
                    : failureClassifier;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            long started = System.nanoTime();
            try {
                ChatResponse response = delegate.chat(messages);
                boolean success = responseHasText(response) && !(delegate instanceof ExpectedFailureChatModel);
                record(success, success ? LlmFailureClass.NONE : LlmFailureClass.UNKNOWN, started);
                return response;
            } catch (IllegalArgumentException | IllegalStateException ex) {
                LlmFailureClass failureClass = failureClassifier.classify(ex);
                record(false, failureClass, started);
                throw ex;
            } catch (NullPointerException | UnsupportedOperationException | UncheckedIOException ex) {
                LlmFailureClass failureClass = failureClassifier.classify(ex);
                record(false, failureClass, started);
                throw ex;
            }
        }

        private void record(boolean success, LlmFailureClass failureClass, long startedNanos) {
            long latencyMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            LlmFailureClass safeFailureClass = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
            if (bandit != null) {
                bandit.recordOutcome(key, success, latencyMs, safeFailureClass);
            }
            try {
                TraceStore.put("llmrouter.bandit.rewardRecorded", true);
                TraceStore.put("llmrouter.bandit.reward", success ? "success" : "fail");
                TraceStore.put("llmrouter.bandit.failureClass",
                        safeFailureClass.name().toLowerCase(Locale.ROOT));
                TraceStore.put("llmrouter.bandit.latencyMs", latencyMs);
                TraceStore.put("llmrouter.bandit.routeKeyHash", SafeRedactor.hashValue(key));
                TraceStore.put("llmrouter.bandit.routeKeyLength", key == null ? 0 : key.length());
            } catch (IllegalArgumentException | IllegalStateException ignore) {
                traceSuppressed("bandit.record", ignore);
                // best-effort diagnostics only
            }
        }

        private static boolean responseHasText(ChatResponse response) {
            return response != null
                    && response.aiMessage() != null
                    && StringUtils.hasText(response.aiMessage().text());
        }
    }

    private static final class CallArgs {
        final String requestedModelId;
        final Double temperature;
        final Double topP;
        final Double frequencyPenalty;
        final Double presencePenalty;
        final Integer maxTokens;
        final int timeoutSeconds;
        final long timeoutMs;

        private CallArgs(
                String requestedModelId,
                Double temperature,
                Double topP,
                Double frequencyPenalty,
                Double presencePenalty,
                Integer maxTokens,
                int timeoutSeconds) {
            this.requestedModelId = requestedModelId;
            this.temperature = temperature;
            this.topP = topP;
            this.frequencyPenalty = frequencyPenalty;
            this.presencePenalty = presencePenalty;
            this.maxTokens = maxTokens;
            this.timeoutSeconds = timeoutSeconds;
            this.timeoutMs = (long) timeoutSeconds * 1000L;
        }

        static CallArgs parse(Object[] args) {
            if (args == null || args.length == 0) {
                return null;
            }
            if (!(args[0] instanceof String modelId)) {
                return null;
            }

            // overload 1: (String, Double, Double, Integer, int)
            if (args.length == 5) {
                return new CallArgs(
                        modelId,
                        safeDouble(args[1]),
                        safeDouble(args[2]),
                        null,
                        null,
                        safeIntObj(args[3]),
                        safeInt(args[4]));
            }

            // overload 2: (String, Double, Double, Double, Double, Integer, int)
            if (args.length == 7) {
                return new CallArgs(
                        modelId,
                        safeDouble(args[1]),
                        safeDouble(args[2]),
                        safeDouble(args[3]),
                        safeDouble(args[4]),
                        safeIntObj(args[5]),
                        safeInt(args[6]));
            }

            return null;
        }

        private static Double safeDouble(Object o) {
            if (o == null) {
                return null;
            }
            if (o instanceof Double d) {
                return d;
            }
            if (o instanceof Number n) {
                return n.doubleValue();
            }
            return null;
        }

        private static Integer safeIntObj(Object o) {
            if (o == null) {
                return null;
            }
            if (o instanceof Integer i) {
                return i;
            }
            if (o instanceof Number n) {
                return n.intValue();
            }
            return null;
        }

        private static int safeInt(Object o) {
            if (o == null) {
                return 0;
            }
            if (o instanceof Integer i) {
                return i;
            }
            if (o instanceof Number n) {
                return n.intValue();
            }
            return 0;
        }
    }
}
