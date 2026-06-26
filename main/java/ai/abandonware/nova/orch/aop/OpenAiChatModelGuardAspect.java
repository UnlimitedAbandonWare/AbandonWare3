package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import ai.abandonware.nova.orch.llm.ModelGuardSupport;
import ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import com.example.lms.guard.KeyResolver;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import org.springframework.beans.factory.ObjectProvider;

/**
 * AOP guard for {@code DynamicChatModelFactory.lcWithTimeout(...)} to prevent routing Responses-only models
 * to the Chat Completions endpoint.
 *
 * <p>Key behavior: fail-soft and proceed-ensure (never call proceed() more than once).</p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class OpenAiChatModelGuardAspect {

    private final NovaModelGuardProperties props;
    private final Environment env;
    private final ObjectProvider<KeyResolver> keyResolverProvider;

    public OpenAiChatModelGuardAspect(NovaModelGuardProperties props,
                                     Environment env,
                                     ObjectProvider<KeyResolver> keyResolverProvider) {
        this.props = props;
        this.env = env;
        this.keyResolverProvider = keyResolverProvider;
    }

    @Around("execution(* com.example.lms.llm.DynamicChatModelFactory.lcWithTimeout(..))")
    public Object guardLcWithTimeout(ProceedingJoinPoint pjp) throws Throwable {
        if (props == null || !props.isEnabled()) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();
        if (args == null || args.length < 1) {
            return pjp.proceed();
        }

        String requestedModel = (args[0] instanceof String s) ? s : null;
        if (!StringUtils.hasText(requestedModel)) {
            return pjp.proceed();
        }

        if (!ModelGuardSupport.isResponsesOnlyModel(requestedModel, props.getResponsesOnlyPrefixes())) {
            return pjp.proceed();
        }

        String openAiBaseUrl = resolveOpenAiBaseUrl();
        if (props.isOpenAiBaseOnly() && !ModelGuardSupport.looksLikeOpenAiBaseUrl(openAiBaseUrl)) {
            return pjp.proceed();
        }

        long timeoutMs = extractTimeoutMs(args);

        String canonicalModel = ModelGuardSupport.canonicalModelName(requestedModel);
        TraceStore.put("llm.modelGuard.requestedModelHash", SafeRedactor.hashValue(canonicalModel));
        TraceStore.put("llm.modelGuard.requestedModelLength", canonicalModel == null ? 0 : canonicalModel.length());
        TraceStore.put("llm.modelGuard.mode", props.getMode().name());

        return switch (props.getMode()) {
            case FAIL_FAST -> expectedFailure(
                    requestedModel,
                    "/v1/chat/completions",
                    "FAIL_FAST",
                    "responses_only_model_on_chat_completions_endpoint");

            case SUBSTITUTE_CHAT -> {
                String substitute = resolveSubstituteChatModel();
                if (!StringUtils.hasText(substitute)) {
                    yield expectedFailure(
                            requestedModel,
                            "/v1/chat/completions",
                            "SUBSTITUTE_CHAT(no_substitute_configured)",
                            "no_substitute_configured");
                }
                TraceStore.put("llm.modelGuard.substituteChatModelHash", SafeRedactor.hashValue(substitute));
                TraceStore.put("llm.modelGuard.substituteChatModelLength", substitute.length());
                if (ModelGuardSupport.isResponsesOnlyModel(substitute, props.getResponsesOnlyPrefixes())) {
                    yield expectedFailure(
                            requestedModel,
                            "/v1/chat/completions",
                            "SUBSTITUTE_CHAT(no_chat_compatible_substitute)",
                            "responses_only_model_on_chat_completions_endpoint");
                }
                Object[] cloned = args.clone();
                cloned[0] = substitute;
                yield pjp.proceed(cloned);
            }

            case ROUTE_RESPONSES -> {
                String apiKey = resolveOpenAiApiKey();
                if (!StringUtils.hasText(apiKey)) {
                    yield expectedFailure(
                            requestedModel,
                            "/v1/responses",
                            "ROUTE_RESPONSES(no_api_key)",
                            "no_api_key_for_responses_endpoint");
                }
                yield new OpenAiResponsesChatModel(openAiBaseUrl, apiKey, requestedModel, timeoutMs);
            }
        };
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
                debugModelName(requestedModel));
    }

    private String resolveOpenAiBaseUrl() {
        String baseUrl = env.getProperty("llm.base-url-openai");
        if (StringUtils.hasText(baseUrl)) {
            return baseUrl;
        }
        baseUrl = env.getProperty("llm.base-url");
        return StringUtils.hasText(baseUrl) ? baseUrl : "";
    }

    @Nullable
    private String resolveOpenAiApiKey() {
        KeyResolver kr = keyResolverProvider.getIfAvailable();
        if (kr == null) {
            return null;
        }
        return kr.getPropertyOrEnvOpenAiKey();
    }

    private String resolveSubstituteChatModel() {
        String m = props.getSubstituteChatModel();
        if (StringUtils.hasText(m)) {
            return m;
        }
        m = env.getProperty("llm.chat-model");
        return StringUtils.hasText(m) ? m : "";
    }

    private static String debugModelName(String requestedModel) {
        return SafeRedactor.hashValue(ModelGuardSupport.canonicalModelName(requestedModel));
    }

    private static long extractTimeoutMs(Object[] args) {
        if (args.length >= 3 && args[2] instanceof Number n) {
            return n.longValue();
        }
        TraceStore.put("modelGuard.timeoutFallback", true);
        return 30_000L;
    }
}
