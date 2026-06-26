package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import ai.abandonware.nova.orch.llm.ModelGuardSupport;
import com.example.lms.guard.KeyResolver;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.ChatMessage;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelGuardExpectedFailureContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void defaultModeReturnsExpectedFailureInsteadOfProceedingForResponsesOnlyModel() throws Throwable {
        NovaModelGuardProperties props = new NovaModelGuardProperties();
        assertEquals(NovaModelGuardProperties.Mode.FAIL_FAST, props.getMode());

        OpenAiChatModelGuardAspect aspect = new OpenAiChatModelGuardAspect(
                props,
                new MockEnvironment().withProperty("llm.base-url", "https://api.openai.com/v1"),
                unavailableKeyResolver());
        ProceedingJoinPoint pjp = pjpForModel("o3-deep-research");

        Object result = aspect.guardLcWithTimeout(pjp);

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, result);
        String message = model.chat(List.<ChatMessage>of()).aiMessage().text();
        assertTrue(message.contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"));
        assertTrue(message.contains("actionTaken: FAIL_FAST"));
        assertEquals(Boolean.TRUE, TraceStore.get("llm.modelGuard.triggered"));
        assertEquals("FAIL_FAST", TraceStore.get("llm.modelGuard.mode"));
        assertEquals("/v1/chat/completions", TraceStore.get("llm.modelGuard.endpoint"));
        assertEquals("responses_only_model_on_chat_completions_endpoint",
                TraceStore.get("llm.modelGuard.failReason"));
        verify(pjp, never()).proceed();
    }

    @Test
    void guardCanonicalizesCaseAndOrchestrationTagsBeforeResponsesOnlyMatch() throws Throwable {
        NovaModelGuardProperties props = new NovaModelGuardProperties();

        OpenAiChatModelGuardAspect aspect = new OpenAiChatModelGuardAspect(
                props,
                new MockEnvironment().withProperty("llm.base-url", "https://api.openai.com/v1"),
                unavailableKeyResolver());
        ProceedingJoinPoint pjp = pjpForModel("LC:O3-DEEP-RESEARCH:fallback");

        Object result = aspect.guardLcWithTimeout(pjp);

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, result);
        String message = model.chat(List.<ChatMessage>of()).aiMessage().text();
        assertTrue(message.contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"));
        verify(pjp, never()).proceed();
    }

    @Test
    void substituteChatWithoutConfiguredSubstituteReturnsExpectedFailure() throws Throwable {
        NovaModelGuardProperties props = new NovaModelGuardProperties();
        props.setMode(NovaModelGuardProperties.Mode.SUBSTITUTE_CHAT);

        OpenAiChatModelGuardAspect aspect = new OpenAiChatModelGuardAspect(
                props,
                new MockEnvironment().withProperty("llm.base-url", "https://api.openai.com/v1"),
                unavailableKeyResolver());
        ProceedingJoinPoint pjp = pjpForModel("o3-deep-research");

        Object result = aspect.guardLcWithTimeout(pjp);

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, result);
        String message = model.chat(List.<ChatMessage>of()).aiMessage().text();
        assertTrue(message.contains("SUBSTITUTE_CHAT(no_substitute_configured)"));
        verify(pjp, never()).proceed();
    }

    @Test
    void substituteChatRejectsResponsesOnlySubstituteInsteadOfProceeding() throws Throwable {
        NovaModelGuardProperties props = new NovaModelGuardProperties();
        props.setMode(NovaModelGuardProperties.Mode.SUBSTITUTE_CHAT);

        OpenAiChatModelGuardAspect aspect = new OpenAiChatModelGuardAspect(
                props,
                new MockEnvironment()
                        .withProperty("llm.base-url", "https://api.openai.com/v1")
                        .withProperty("llm.chat-model", "gpt-5-pro"),
                unavailableKeyResolver());
        ProceedingJoinPoint pjp = pjpForModel("gpt-5-pro");

        Object result = aspect.guardLcWithTimeout(pjp);

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, result);
        String message = model.chat(List.<ChatMessage>of()).aiMessage().text();
        assertTrue(message.contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"));
        assertTrue(message.contains("SUBSTITUTE_CHAT(no_chat_compatible_substitute)"));
        verify(pjp, never()).proceed();
    }

    @Test
    void missingTimeoutArgumentLeavesFallbackBreadcrumb() throws Throwable {
        NovaModelGuardProperties props = new NovaModelGuardProperties();
        props.setMode(NovaModelGuardProperties.Mode.ROUTE_RESPONSES);

        OpenAiChatModelGuardAspect aspect = new OpenAiChatModelGuardAspect(
                props,
                new MockEnvironment().withProperty("llm.base-url", "https://api.openai.com/v1"),
                unavailableKeyResolver());
        ProceedingJoinPoint pjp = pjpForArgs("gpt-5-pro");

        Object result = aspect.guardLcWithTimeout(pjp);

        assertInstanceOf(ExpectedFailureChatModel.class, result);
        assertEquals(Boolean.TRUE, TraceStore.get("modelGuard.timeoutFallback"));
        verify(pjp, never()).proceed();
    }

    @Test
    void expectedFailureMessageDoesNotRenderSecretShapedRequestedModel() {
        String secretShapedModel = "sk-" + "abcdefghijklmnopqrstuvwxyz123456";

        String message = ModelGuardSupport.buildExpectedFailureMessage(
                secretShapedModel,
                "/v1/chat/completions",
                "FAIL_FAST");

        assertFalse(message.contains(secretShapedModel));
    }

    @Test
    void expectedFailureDebugNameDoesNotRenderRawRequestedModel() throws Throwable {
        NovaModelGuardProperties props = new NovaModelGuardProperties();
        String requestedModel = "gpt-5-pro";
        OpenAiChatModelGuardAspect aspect = new OpenAiChatModelGuardAspect(
                props,
                new MockEnvironment().withProperty("llm.base-url", "https://api.openai.com/v1"),
                unavailableKeyResolver());

        Object result = aspect.guardLcWithTimeout(pjpForModel(requestedModel));

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, result);
        assertFalse(model.toString().contains(requestedModel));
        assertTrue(model.toString().contains("hash:"));
    }

    @Test
    void expectedFailureReasonUsesReadableEndpointMismatchContract() {
        String message = ModelGuardSupport.buildExpectedFailureMessage(
                "gpt-5",
                "/v1/chat/completions",
                "FAIL_FAST");

        assertTrue(message.contains(
                "reason: responses-only model cannot be used on chat-completions endpoint"), message);
    }

    @Test
    void expectedFailureMessageRedactsEndpointOutsideAllowlist() {
        String endpoint = "https://untrusted.example/v1/chat/completions?api_key=redacted-local-placeholder";

        String message = ModelGuardSupport.buildExpectedFailureMessage(
                "gpt-5",
                endpoint,
                "FAIL_FAST");

        assertTrue(message.contains("endpoint: <REDACTED>"), message);
        assertFalse(message.contains("untrusted.example"), message);
        assertFalse(message.contains("api_key="), message);
    }

    @Test
    void modelGuardTraceWritesAreNotSilentlySwallowed() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/OpenAiChatModelGuardAspect.java"));

        assertFalse(source.contains("catch (Exception ignore)"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KeyResolver> unavailableKeyResolver() {
        ObjectProvider<KeyResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static ProceedingJoinPoint pjpForModel(String model) throws Throwable {
        return pjpForArgs(model, null, 1000L);
    }

    private static ProceedingJoinPoint pjpForArgs(Object... args) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn("PROCEEDED");
        return pjp;
    }
}
