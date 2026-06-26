package ai.abandonware.nova.orch.web;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitBackoffCoordinatorRedactionTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void arbitraryProviderNameDoesNotBecomeRawTraceKeySegment() {
        String rawProvider = "ownertoken-" + "sk-" + "12345678901234567890";
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());

        backoff.recordRateLimited(rawProvider, 1_000L, "rate_limit");

        String rendered = String.valueOf(TraceStore.getAll()).toLowerCase(Locale.ROOT);
        assertFalse(rendered.contains(rawProvider.toLowerCase(Locale.ROOT)), rendered);
        assertFalse(rendered.contains("ownertoken"), rendered);
        assertFalse(rendered.contains("sk-" + "12345678901234567890"), rendered);
        assertTrue(rendered.contains("web.failsoft.ratelimitbackoff.hash_"), rendered);
    }

    @Test
    void decisionReasonDoesNotExposeFreeFormBackoffReason() {
        String privateReason = "private student query timeout retry";
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());

        backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, 1_000L, privateReason);
        RateLimitBackoffCoordinator.Decision decision = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);

        assertTrue(decision.shouldSkip());
        assertFalse(decision.reason().contains(privateReason), decision.reason());
        assertFalse(decision.reason().contains("private student"), decision.reason());
        assertFalse(decision.reason().contains("timeout retry"), decision.reason());
        assertTrue(decision.reason().startsWith("hash:"), decision.reason());
    }

    @Test
    void canonicalWebProviderConstantsCoverAllRuntimeProviders() {
        assertEquals("naver", RateLimitBackoffCoordinator.PROVIDER_NAVER);
        assertEquals("brave", RateLimitBackoffCoordinator.PROVIDER_BRAVE);
        assertEquals("serpapi", RateLimitBackoffCoordinator.PROVIDER_SERPAPI);
        assertEquals("tavily", RateLimitBackoffCoordinator.PROVIDER_TAVILY);
    }

    @Test
    void invalidNumericBackoffValueUsesStableReasonCodeWithoutRawValue() throws Exception {
        Method method = RateLimitBackoffCoordinator.class.getDeclaredMethod("toLong", Object.class);
        method.setAccessible(true);

        long value = (Long) method.invoke(null, "ownerToken-not-a-long");

        assertEquals(0L, value);
        assertEquals("toLong", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken-not-a-long"));
        assertFalse(trace.contains("NumberFormatException"));
    }

    @Test
    void nonFiniteNumericBackoffValueUsesStableReasonCode() throws Exception {
        Method method = RateLimitBackoffCoordinator.class.getDeclaredMethod("toLong", Object.class);
        method.setAccessible(true);

        long value = (Long) method.invoke(null, Double.POSITIVE_INFINITY);

        assertEquals(0L, value);
        assertEquals("toLong", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(String.valueOf(Long.MAX_VALUE)), trace);
        assertFalse(trace.contains("Infinity"), trace);
    }

    @Test
    void nonFiniteDoubleConfigFallsBackWithStableReasonCode() throws Exception {
        MockEnvironment env = new MockEnvironment()
                .withProperty("nova.orch.web.failsoft.ratelimit-backoff.jitter-ratio", "NaN");
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(env);
        Method method = RateLimitBackoffCoordinator.class.getDeclaredMethod(
                "getDouble", double.class, String[].class);
        method.setAccessible(true);

        double value = (Double) method.invoke(backoff, 0.20d,
                new String[] { "nova.orch.web.failsoft.ratelimit-backoff.jitter-ratio" });

        assertEquals(0.20d, value);
        assertEquals("getDouble", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NaN"));
    }

    @Test
    void retryAfterParseFailuresUseStableReasonCodes() throws Exception {
        Method method = RateLimitBackoffCoordinator.class.getDeclaredMethod(
                "errorType", String.class, Throwable.class);
        method.setAccessible(true);

        assertEquals("invalid_number", method.invoke(null,
                "parseRetryAfterMs.delta", new NumberFormatException("private-token")));
        assertEquals("invalid_date", method.invoke(null,
                "parseRetryAfterMs.rfc1123",
                new java.time.format.DateTimeParseException("private-token", "private-token", 0)));
        assertEquals("invalid_date", method.invoke(null,
                "parseRetryAfterMs.zoned",
                new java.time.DateTimeException("private-token")));
    }

    @Test
    void longParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/web/RateLimitBackoffCoordinator.java"))
                .replace("\r\n", "\n");

        assertFalse(source.contains("catch (Exception ignore) {\n            return 0L;\n        }"));
        assertFalse(source.contains("catch (Throwable ignore)"));
        assertFalse(source.contains("catch (Throwable ignore2)"));
        assertFalse(source.contains("catch (NumberFormatException ignore)"));
        assertFalse(source.contains("catch (DateTimeParseException ignore)"));
        assertTrue(source.contains("traceSuppressed(\"recordRateLimited.trace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"recordLocalRateLimit.trace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"recordFailure.awaitDisabledTrace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"recordFailure.trace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"hardCap.zero100Trace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"toLong\", e);"));
        assertTrue(source.contains("traceSuppressed(\"extractTimeoutHintMs\", e);"));
        assertTrue(source.contains("traceSuppressed(\"parseRetryAfterMs.delta\", e);"));
        assertTrue(source.contains("traceSuppressed(\"parseRetryAfterMs.rfc1123\", e);"));
        assertTrue(source.contains("traceSuppressed(\"parseRetryAfterMs.zoned\", e2);"));
        assertTrue(source.contains("traceSuppressed(\"getLong\", e);"));
        assertTrue(source.contains("traceSuppressed(\"getDouble\", e);"));
        assertTrue(source.contains("MDC.put(\"web.failsoft.rateLimitBackoff.suppressed.stage\", stage);"));
        assertFalse(source.contains("e.getMessage()"));
    }
}
