package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CleanOutputRedactionAspectTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void blankChatResultUsesReadableFallback() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(ChatResult.of("", "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("The answer body was blank. Please retry the request.", result.content());
        assertEquals(Boolean.TRUE, TraceStore.get("orch.output.blank.prevented"));
        assertEquals("blank_content", TraceStore.get("orch.output.blank.prevented.reason"));
        assertFalse(result.content().contains("?"));
    }

    @Test
    void markerOnlyChatResultUsesReadableFallback() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(ChatResult.of("<!-- NOVA_TRACE_INJECTED -->", "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("The answer body was removed while cleaning diagnostics. Please retry the request.",
                result.content());
        assertEquals(Boolean.TRUE, TraceStore.get("orch.output.blank.prevented"));
        assertEquals("diagnostics_removed_empty", TraceStore.get("orch.output.blank.prevented.reason"));
        assertEquals("<!-- NOVA_TRACE_INJECTED -->:head", TraceStore.get("orch.output.blank.prevented.marker"));
        assertFalse(result.content().contains("?"));
    }

    @Test
    void headMarkerWithDiagnosticsDoesNotBecomeVisibleAnswer() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String diagnosticsFirst = "<!-- NOVA_TRACE_INJECTED -->\n"
                + "private diagnostics ownerToken=secret\n\n"
                + "normal answer";
        when(pjp.proceed()).thenReturn(ChatResult.of(diagnosticsFirst, "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("The answer body was removed while cleaning diagnostics. Please retry the request.",
                result.content());
        assertFalse(result.content().contains("private diagnostics"));
        assertFalse(result.content().contains("ownerToken"));
        assertFalse(result.content().contains("normal answer"));
    }

    @Test
    void inlineDiagnosticTokenInNormalAnswerIsNotCut() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String answer = "TRACE_JSON is the dedicated structured trace logger name, not user diagnostics.";
        when(pjp.proceed()).thenReturn(ChatResult.of(answer, "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals(answer, result.content());
        assertFalse(Boolean.TRUE.equals(TraceStore.get("orch.output.redaction.applied")));
    }

    @Test
    void legacyLineStartTraceMarkerStillCutsDiagnosticTail() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(ChatResult.of(
                "normal answer\nTRACE_JSON {\"ownerToken\":\"secret\"}",
                "model",
                false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("normal answer", result.content());
        assertFalse(result.content().contains("ownerToken"));
        assertTrue(Boolean.TRUE.equals(TraceStore.get("orch.output.redaction.applied")));
    }

    @Test
    void failSoftTraceAndHashFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/CleanOutputRedactionAspect.java"));

        assertTrue(source.contains("traceSuppressed(\"blank.prevented\", ignored);"));
        assertTrue(source.contains("traceSuppressed(\"blank.prevented.marker\", ignored);"));
        assertTrue(source.contains("traceSuppressed(\"redaction.applied\", ignored);"));
        assertTrue(source.contains("traceSuppressed(\"removedHash.sha1\", e);"));
        assertTrue(source.contains("CleanOutputRedactionAspect trace fallback (stage={} errorHash={} errorLength={})"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(error)), messageLength(error)"));
    }
}
