package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.router.LlmRouterContext;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.llm.gateway.LlmRouteScorer;
import com.example.lms.llm.spec.ModelSpecRegistry;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmRouterGatewaySecurityTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        LlmRouterContext.clear();
    }

    @Test
    void modelGuardTraceDoesNotStoreRawModelIdentifiers() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"llm.modelGuard.requestedModel\""));
        assertFalse(source.contains("TraceStore.put(\"llm.modelGuard.substituteChatModel\""));
        assertTrue(source.contains("TraceStore.put(\"llm.modelGuard.requestedModelHash\""));
        assertTrue(source.contains("TraceStore.put(\"llm.modelGuard.requestedModelLength\""));
        assertTrue(source.contains("TraceStore.put(\"llm.modelGuard.substituteChatModelHash\""));
        assertTrue(source.contains("TraceStore.put(\"llm.modelGuard.substituteChatModelLength\""));

        String guard = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/OpenAiChatModelGuardAspect.java"),
                StandardCharsets.UTF_8);
        assertFalse(guard.contains("TraceStore.put(\"llm.modelGuard.requestedModel\", ModelGuardSupport.canonicalModelName(requestedModel))"));
        assertFalse(guard.contains("TraceStore.put(\"llm.modelGuard.substituteChatModel\", substitute)"));
        assertTrue(guard.contains("TraceStore.put(\"llm.modelGuard.requestedModelHash\""));
        assertTrue(guard.contains("TraceStore.put(\"llm.modelGuard.requestedModelLength\""));
        assertTrue(guard.contains("TraceStore.put(\"llm.modelGuard.substituteChatModelHash\""));
        assertTrue(guard.contains("TraceStore.put(\"llm.modelGuard.substituteChatModelLength\""));
    }

    @Test
    void modelGuardSubstituteChatRejectsResponsesOnlySubstituteForRouterRoutes() throws Throwable {
        MockEnvironment env = baseEnv()
                .withProperty("llm.chat-model", "gpt-5-pro")
                .withProperty("OPENAI_API_KEY", "valid-test-key");
        LlmRouterProperties props = props("openai", "gpt-5-pro", "https://api.openai.com/v1");
        NovaModelGuardProperties modelGuard = new NovaModelGuardProperties();
        modelGuard.setMode(NovaModelGuardProperties.Mode.SUBSTITUTE_CHAT);
        LlmRouterAspect aspect = aspect(env, props, modelGuard);

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.openai",
                null,
                null,
                null,
                10));

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, out);
        String message = model.chat(List.of()).aiMessage().text();
        assertTrue(message.contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), message);
        assertTrue(message.contains("SUBSTITUTE_CHAT(no_chat_compatible_substitute)"), message);
        assertNull(LlmRouterContext.get());
    }

    @Test
    void modelGuardFailFastRouterExpectedFailureWritesReasonAndEndpointTrace() throws Throwable {
        MockEnvironment env = baseEnv()
                .withProperty("OPENAI_API_KEY", "valid-test-key");
        LlmRouterProperties props = props("openai", "gpt-5-pro", "https://api.openai.com/v1");
        NovaModelGuardProperties modelGuard = new NovaModelGuardProperties();
        modelGuard.setMode(NovaModelGuardProperties.Mode.FAIL_FAST);
        LlmRouterAspect aspect = aspect(env, props, modelGuard);

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.openai",
                null,
                null,
                null,
                10));

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, out);
        assertTrue(model.chat(List.of()).aiMessage().text().contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"));
        assertEquals(Boolean.TRUE, TraceStore.get("llm.modelGuard.triggered"));
        assertEquals("FAIL_FAST", TraceStore.get("llm.modelGuard.mode"));
        assertEquals("/v1/chat/completions", TraceStore.get("llm.modelGuard.endpoint"));
        assertEquals("responses_only_model_on_chat_completions_endpoint",
                TraceStore.get("llm.modelGuard.failReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("gpt-5-pro"));
        assertFalse(model.toString().contains("gpt-5-pro"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void modelGuardSubstituteChatWithoutRouterSubstituteReturnsExpectedFailure() throws Throwable {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.owner-token-header", "X-Owner-Token")
                .withProperty("OPENAI_API_KEY", "valid-test-key");
        LlmRouterProperties props = props("openai", "gpt-5-pro", "https://api.openai.com/v1");
        NovaModelGuardProperties modelGuard = new NovaModelGuardProperties();
        modelGuard.setMode(NovaModelGuardProperties.Mode.SUBSTITUTE_CHAT);
        LlmRouterAspect aspect = aspect(env, props, modelGuard);

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.openai",
                null,
                null,
                null,
                10));

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, out);
        String message = model.chat(List.of()).aiMessage().text();
        assertTrue(message.contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), message);
        assertTrue(message.contains("SUBSTITUTE_CHAT(no_substitute_configured)"), message);
        assertNull(LlmRouterContext.get());
    }

    @Test
    void apiDisabledReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"llmrouter.api.disabledReason\", disabledReason);"));
        assertTrue(source.contains(
                "TraceStore.put(\"llmrouter.api.disabledReason\", SafeRedactor.traceLabelOrFallback(disabledReason, \"unknown\"));"));
    }

    @Test
    void apiDisabledReasonExceptionUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("\" disabledReason=\" + disabledReason"));
        assertTrue(source.contains("+ SafeRedactor.traceLabelOrFallback(disabledReason, \"unknown\"));"));
    }

    @Test
    void llmRouterAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "llm router diagnostics need redacted breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void llmRouterHelperCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"config.get\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"bandit.record\", ignore);"));
    }

    @Test
    void llmRouterSuppressedTraceHelperRecordsRedactedStageAndErrorType() throws Exception {
        String secret = "sk-" + "llmRouterSuppressedSecret123456789";
        Method method = LlmRouterAspect.class.getDeclaredMethod(
                "traceSuppressed",
                String.class,
                Exception.class);
        method.setAccessible(true);

        method.invoke(null, "providerDisabled.trace " + secret, new IllegalStateException("raw " + secret));

        Object stage = TraceStore.get("llmrouter.suppressed.stage");
        assertTrue(String.valueOf(stage).startsWith("hash:"), String.valueOf(stage));
        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.suppressed." + stage));
        assertEquals("IllegalStateException", TraceStore.get("llmrouter.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("llmrouter.suppressed." + stage + ".errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(secret), trace);
    }

    @Test
    void remoteMacMiniRouteFailsClosedWithoutAllowlist() {
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local");
        LlmRouterAspect aspect = aspect(env, props("gemma", "gemma3:4b", "https://macmini-ollama.internal/v1"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.gemma",
                        null,
                        null,
                        null,
                        10)));

        assertFalse(ex.getMessage().contains("sk-local"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void remoteMacMiniRouteAllowsAllowlistedOwnerToken() throws Throwable {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local")
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("llm.provider-guard.allow-private-remote", "true")
                .withProperty("llm.provider-guard.allowed-hosts", "macmini-ollama.internal");
        LlmRouterAspect aspect = aspect(env, props("gemma", "gemma3:4b", "https://macmini-ollama.internal/v1"));

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.gemma",
                null,
                null,
                null,
                10));

        assertInstanceOf(ChatModel.class, out);
        assertEquals("gemma", LlmRouterContext.get().key());
        assertEquals("macmini-ollama.internal", TraceStore.get("llmrouter.endpointHost"));
        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
    }

    @Test
    void localApiKeyResolverSkipsSkLocalAndUsesRealEnvKey() {
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local")
                .withProperty("LLM_API_KEY", "local_real_key");
        LlmRouterAspect aspect = aspect(env, props("gemma", "gemma3:4b", "http://localhost:11434/v1"));

        String resolved = ReflectionTestUtils.invokeMethod(aspect, "resolveLocalApiKey");

        assertEquals("local_real_key", resolved);
    }

    @Test
    void externalOpenRouterRouteDoesNotReceiveOwnerToken() throws Throwable {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("OPENROUTER_API_KEY", "openrouter-secret-value");
        LlmRouterAspect aspect = aspect(env, props("api3", "qwen/qwen3-32b", "https://api.openrouter.ai/api/v1"));

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.api3",
                null,
                null,
                null,
                10));

        assertInstanceOf(ChatModel.class, out);
        assertEquals("api3", LlmRouterContext.get().key());
        assertNull(TraceStore.get("llmrouter.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
    }

    @Test
    void disabledMacMiniRouteDoesNotFallBackToLocalDefaults() {
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local");
        LlmRouterProperties props = props("macmini", "gemma3:4b", "https://macmini-ollama.internal/v1");
        props.getModels().get("macmini").setEnabled(false);
        LlmRouterAspect aspect = aspect(env, props);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.macmini",
                        null,
                        null,
                        null,
                        10)));

        assertEquals("provider=llmrouter.macmini disabledReason=route_disabled", ex.getMessage());
        assertEquals("macmini", TraceStore.get("llmrouter.route.key"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.route.enabled"));
        assertEquals("route_disabled", TraceStore.get("llmrouter.api.disabledReason"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void disabledSensitiveRouteKeyUsesDiagnosticLabelOnly() {
        String sensitiveRoute = "private-route-token=owner-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local");
        LlmRouterProperties props = props(sensitiveRoute, "gemma3:4b", "https://macmini-ollama.internal/v1");
        props.getModels().get(sensitiveRoute).setEnabled(false);
        LlmRouterAspect aspect = aspect(env, props);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter." + sensitiveRoute,
                        null,
                        null,
                        null,
                        10)));

        String routeTrace = String.valueOf(TraceStore.get("llmrouter.route.key"));
        assertFalse(ex.getMessage().contains(sensitiveRoute), ex.getMessage());
        assertFalse(routeTrace.contains(sensitiveRoute), routeTrace);
        assertTrue(routeTrace.startsWith("hash:"), routeTrace);
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.route.enabled"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void blankExternalRouteDoesNotFallBackToLocalDefaults() {
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local");
        LlmRouterAspect aspect = aspect(env, props("external", "", ""));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.external",
                        null,
                        null,
                        null,
                        10)));

        assertEquals("provider=llmrouter.external disabledReason=missing_route_config", ex.getMessage());
        assertEquals("missing_route_config", TraceStore.get("llmrouter.api.disabledReason"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void externalOpenRouterRouteRequiresProviderSpecificKey() {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("llm.api-key", "sk-local");
        LlmRouterAspect aspect = aspect(env, props("external", "openrouter/auto", "https://api.openrouter.ai/api/v1"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.external",
                        null,
                        null,
                        null,
                        10)));

        assertEquals("provider=openrouter disabledReason="
                + SafeRedactor.hashValue("missing OPENROUTER_API_KEY"), ex.getMessage());
        assertEquals("openrouter", TraceStore.get("llmrouter.api.provider"));
        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.api.providerDisabled"));
        assertEquals("provider-disabled", TraceStore.get("llmrouter.api.failureClass"));
        assertEquals("api.openrouter.ai", TraceStore.get("llmrouter.api.endpointHost"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.api.hasKey"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.api.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void externalOpenCodeRouteRequiresProviderSpecificKey() {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("llm.api-key", "sk-local");
        LlmRouterAspect aspect = aspect(env,
                props("external", "deepseek-v4-flash-free", "https://opencode.ai/zen/v1"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.external",
                        null,
                        null,
                        null,
                        10)));

        assertEquals("provider=opencode disabledReason="
                + SafeRedactor.hashValue("missing OPENCODE_API_KEY"), ex.getMessage());
        assertEquals("opencode", TraceStore.get("llmrouter.api.provider"));
        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.api.providerDisabled"));
        assertEquals("provider-disabled", TraceStore.get("llmrouter.api.failureClass"));
        assertEquals("opencode.ai", TraceStore.get("llmrouter.api.endpointHost"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.api.hasKey"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.api.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void externalOpenCodeRouteDoesNotReceiveOwnerToken() throws Throwable {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("OPENCODE_API_KEY", "opencode-secret-value");
        LlmRouterAspect aspect = aspect(env,
                props("external", "deepseek-v4-flash-free", "https://opencode.ai/zen/v1"));

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.external",
                null,
                null,
                null,
                10));

        assertInstanceOf(ChatModel.class, out);
        assertEquals("external", LlmRouterContext.get().key());
        assertNull(TraceStore.get("llmrouter.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
        assertFalse(TraceStore.getAll().toString().contains("opencode-secret-value"));
    }

    @Test
    void autoSkipsDisabledAndZeroWeightExternalRoutes() {
        LlmRouterProperties props = new LlmRouterProperties();
        Map<String, LlmRouterProperties.ModelConfig> models = new LinkedHashMap<>();
        models.put("macmini", model("gemma3:4b", "https://macmini-ollama.internal/v1", false, 10.0d));
        models.put("external", model("openrouter/auto", "https://api.openrouter.ai/api/v1", true, 0.0d));
        models.put("gemma", model("gemma3:4b", "http://localhost:11434/v1", true, 1.0d));
        props.setModels(models);

        LlmRouterBandit.Selected selected = new LlmRouterBandit(props).pick("llmrouter.auto");

        assertEquals("gemma", selected.key());
        assertEquals("gemma3:4b", selected.cfg().getName());
    }

    @Test
    void enforceModeAutoSkipsFallbackOnlyRoute() throws Throwable {
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local");
        LlmRouterProperties props = new LlmRouterProperties();
        Map<String, LlmRouterProperties.ModelConfig> models = new LinkedHashMap<>();
        LlmRouterProperties.ModelConfig cloud = model("qwen/qwen3-32b", "https://api.groq.com/openai/v1", true, 10.0d);
        cloud.setFallbackOnly(true);
        models.put("api3", cloud);
        models.put("gemma", model("gemma3:4b", "http://localhost:11434/v1", true, 1.0d));
        props.setModels(models);
        LlmGatewayProperties gatewayProps = new LlmGatewayProperties();
        gatewayProps.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        gatewayProps.getSpecRegistry().setEnabled(false);
        HybridLlmGatewayProbeService gateway = new HybridLlmGatewayProbeService(
                gatewayProps,
                new ModelRuntimeHealthTracker(),
                new ModelSpecRegistry(new ObjectMapper(), gatewayProps),
                new LlmRouteScorer());
        LlmRouterAspect aspect = aspect(env, props, gateway);

        aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.auto",
                null,
                null,
                null,
                10));

        assertEquals("gemma", LlmRouterContext.get().key());
    }

    @Test
    void routedChatModelRecordsBanditOutcomeOnChatSuccess() {
        LlmRouterProperties props = props("gemma", "gemma3:4b", "http://localhost:11434/v1");
        LlmRouterBandit bandit = new LlmRouterBandit(props);
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok"))
                        .build();
            }
        };
        ChatModel recording = new LlmRouterAspect.RecordingChatModel(
                delegate,
                bandit,
                "gemma",
                new LlmGatewayFailureClassifier());

        assertEquals("ok", recording.chat(List.of()).aiMessage().text());

        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.bandit.rewardRecorded"));
        assertEquals("success", TraceStore.get("llmrouter.bandit.reward"));
        assertEquals("none", TraceStore.get("llmrouter.bandit.failureClass"));
        List<?> breadcrumbs = assertInstanceOf(List.class, TraceStore.get("ml.breadcrumbs.v1"));
        Map<?, ?> row = assertInstanceOf(Map.class, breadcrumbs.get(0));
        assertEquals("LlmRouterBandit", row.get("component"));
        assertEquals("llm_router_reward", row.get("decision"));
        Map<?, ?> data = assertInstanceOf(Map.class, row.get("data"));
        assertEquals("gemma", data.get("llmArm"));
        assertEquals(1.0d, data.get("reward"));
        assertEquals("none", data.get("failureClass"));
        assertInstanceOf(Map.class, TraceStore.get("mla.breadcrumb.llm.reward.gemma"));
    }

    @Test
    void routedChatModelRecordsBanditOutcomeOnUncheckedFailure() {
        LlmRouterProperties props = props("gemma", "gemma3:4b", "http://localhost:11434/v1");
        LlmRouterBandit bandit = new LlmRouterBandit(props);
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
                throw new NullPointerException("simulated unchecked model failure");
            }
        };
        ChatModel recording = new LlmRouterAspect.RecordingChatModel(
                delegate,
                bandit,
                "gemma",
                new LlmGatewayFailureClassifier());

        assertThrows(NullPointerException.class, () -> recording.chat(List.of()));

        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.bandit.rewardRecorded"));
        assertEquals("fail", TraceStore.get("llmrouter.bandit.reward"));
        assertEquals("unknown", TraceStore.get("llmrouter.bandit.failureClass"));
    }

    private static MockEnvironment baseEnv() {
        return new MockEnvironment()
                .withProperty("llm.chat-model", "gemma3:4b")
                .withProperty("llm.owner-token-header", "X-Owner-Token");
    }

    private static LlmRouterProperties props(String key, String modelName, String baseUrl) {
        LlmRouterProperties props = new LlmRouterProperties();
        props.setEnabled(true);
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName(modelName);
        cfg.setBaseUrl(baseUrl);
        cfg.setWeight(1.0d);
        props.setModels(Map.of(key, cfg));
        return props;
    }

    private static LlmRouterProperties.ModelConfig model(String modelName, String baseUrl, boolean enabled, double weight) {
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(enabled);
        cfg.setName(modelName);
        cfg.setBaseUrl(baseUrl);
        cfg.setWeight(weight);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(MockEnvironment env, LlmRouterProperties props) {
        return aspect(env, props, (HybridLlmGatewayProbeService) null);
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(MockEnvironment env, LlmRouterProperties props, HybridLlmGatewayProbeService gateway) {
        ObjectProvider<KeyResolver> keyResolverProvider = mock(ObjectProvider.class);
        when(keyResolverProvider.getIfAvailable()).thenReturn(null);
        NovaModelGuardProperties modelGuard = new NovaModelGuardProperties();
        modelGuard.setEnabled(false);
        return aspect(env, props, modelGuard, gateway);
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(MockEnvironment env, LlmRouterProperties props, NovaModelGuardProperties modelGuard) {
        return aspect(env, props, modelGuard, null);
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(MockEnvironment env, LlmRouterProperties props,
                                          NovaModelGuardProperties modelGuard,
                                          HybridLlmGatewayProbeService gateway) {
        ObjectProvider<KeyResolver> keyResolverProvider = mock(ObjectProvider.class);
        when(keyResolverProvider.getIfAvailable()).thenReturn(null);
        return new LlmRouterAspect(
                env,
                props,
                new LlmRouterBandit(props),
                modelGuard,
                keyResolverProvider,
                gateway,
                null,
                new LlmGatewayFailureClassifier());
    }

    private static final class FakePjp implements ProceedingJoinPoint {
        private final Object result;
        private final Object[] args;

        private FakePjp(Object result, Object... args) {
            this.result = result;
            this.args = args;
        }

        @Override
        public Object proceed() {
            return result;
        }

        @Override
        public Object proceed(Object[] args) {
            return result;
        }

        @Override
        public void set$AroundClosure(AroundClosure arc) {
        }

        @Override
        public Object getThis() {
            return this;
        }

        @Override
        public Object getTarget() {
            return this;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public Signature getSignature() {
            return null;
        }

        @Override
        public SourceLocation getSourceLocation() {
            return null;
        }

        @Override
        public String getKind() {
            return "method-execution";
        }

        @Override
        public JoinPoint.StaticPart getStaticPart() {
            return null;
        }

        @Override
        public String toShortString() {
            return "FakePjp";
        }

        @Override
        public String toLongString() {
            return "FakePjp";
        }
    }
}
