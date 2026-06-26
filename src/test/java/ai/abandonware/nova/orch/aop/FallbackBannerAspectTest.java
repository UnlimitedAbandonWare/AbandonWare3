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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FallbackBannerAspectTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void degradedBannerPrependLeavesHashOnlyBreadcrumb() throws Throwable {
        FallbackBannerAspect aspect = new FallbackBannerAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String evidenceOnly = "private evidence-only draft";
        when(pjp.getArgs()).thenReturn(new Object[0]);
        when(pjp.proceed()).thenReturn(ChatResult.of(evidenceOnly, "fallback:evidence:test-model", true));

        ChatResult result = (ChatResult) aspect.aroundContinueChat(pjp);

        assertTrue(result.content().contains("[DEGRADED MODE]"));
        assertTrue(result.content().contains(evidenceOnly));
        assertEquals(Boolean.TRUE, TraceStore.get("fallbackBanner.bannerPrepended"));
        assertEquals("fallback_evidence", TraceStore.get("fallbackBanner.bannerPrepended.reason"));
        assertEquals(evidenceOnly.length(), TraceStore.get("fallbackBanner.bannerPrepended.contentLength"));
        assertNotNull(TraceStore.get("fallbackBanner.bannerPrepended.modelHash"));
        String publicTrace = TraceStore.getAll().toString();
        assertFalse(publicTrace.contains(evidenceOnly));
        assertFalse(publicTrace.contains("test-model"));
    }

    @Test
    void failSoftFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/FallbackBannerAspect.java"));

        assertTrue(source.contains("traceSuppressed(\"answer.mode.trace\", ignoreMode);"));
        assertTrue(source.contains("traceSuppressed(\"aux.canServe\", e);"));
        assertTrue(source.contains("traceSuppressed(\"aux.recovery\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"promptBuilder.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"extractRequest.args\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"config.int\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"config.get\", ignore);"));
        assertTrue(source.contains("[nova][fallback-banner] suppressed stage={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }
}
