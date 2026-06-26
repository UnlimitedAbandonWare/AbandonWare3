package com.example.lms.guard.rulebreak;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBreakEvaluatorTest {

    @Test
    void valueBridgeKeepsNovaRulebreakCanonicalAndToolsFallback() throws Exception {
        Field adminToken = RuleBreakEvaluator.class.getDeclaredField("adminToken");
        Field ttl = RuleBreakEvaluator.class.getDeclaredField("defaultTtl");

        assertEquals("${nova.rulebreak.admin-token:${tools.rulebreak.admin-token:}}",
                adminToken.getAnnotation(Value.class).value());
        assertEquals("${nova.rulebreak.ttl-seconds:${tools.rulebreak.ttl-seconds:60}}",
                ttl.getAnnotation(Value.class).value());
    }

    @Test
    void randomTokenDoesNotActivateRuleBreak() {
        RuleBreakEvaluator evaluator = evaluator("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-RuleBreak-Token", "random-token");

        RuleBreakContext ctx = evaluator.evaluateFromHeaders(request);

        assertFalse(ctx.isValid());
    }

    @Test
    void placeholderAdminTokenDoesNotActivateRuleBreak() {
        RuleBreakEvaluator evaluator = evaluator("${nova.rulebreak.admin-token:${tools.rulebreak.admin-token:}}");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-RuleBreak-Token", "${nova.rulebreak.admin-token:${tools.rulebreak.admin-token:}}");

        RuleBreakContext ctx = evaluator.evaluateFromHeaders(request);

        assertFalse(ctx.isValid());
    }

    @Test
    void validTokenStoresHashOnlyAndPolicyControlsWhitelistBypass() {
        String raw = "valid-rulebreak-token";
        RuleBreakEvaluator evaluator = evaluator(raw);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-RuleBreak-Token", raw);
        request.addHeader("X-RuleBreak-Policy", "OVERRIDE_DOMAINS");

        RuleBreakContext ctx = evaluator.evaluateFromHeaders(request);

        assertTrue(ctx.isValid());
        assertEquals(RuleBreakPolicy.OVERRIDE_DOMAINS, ctx.getPolicy());
        assertTrue(ctx.getPolicy().canBypassWhitelist());
        assertNotEquals(raw, ctx.getTokenHash());
        assertFalse(ctx.getTokenHash().contains(raw));
    }

    @Test
    void trimsHeaderInputsBeforeValidationAndContextStorage() {
        RuleBreakEvaluator evaluator = evaluator("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-RuleBreak-Token", " expected-token ");
        request.addHeader("X-RuleBreak-Policy", " override_domains ");
        request.addHeader("X-Request-Id", " request-1 ");
        request.addHeader("X-Session-Id", " session-1 ");

        RuleBreakContext ctx = evaluator.evaluateFromHeaders(request);

        assertTrue(ctx.isValid());
        assertEquals(RuleBreakPolicy.OVERRIDE_DOMAINS, ctx.getPolicy());
        assertEquals("request-1", ctx.getRequestId());
        assertEquals("session-1", ctx.getSessionId());
        assertFalse(ctx.getTokenHash().contains("expected-token"));
    }

    @Test
    void contextToStringDoesNotExposeRawRequestOrSessionIds() {
        RuleBreakEvaluator evaluator = evaluator("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-RuleBreak-Token", "expected-token");
        request.addHeader("X-Request-Id", "request-secret-123");
        request.addHeader("X-Session-Id", "session-secret-456");

        RuleBreakContext ctx = evaluator.evaluateFromHeaders(request);
        String rendered = String.valueOf(ctx);

        assertNotNull(ctx.getRequestId());
        assertNotNull(ctx.getSessionId());
        assertFalse(rendered.contains("request-secret-123"));
        assertFalse(rendered.contains("session-secret-456"));
        assertTrue(rendered.contains("requestIdHash='hash:"));
        assertTrue(rendered.contains("sessionIdHash='hash:"));
    }

    @Test
    void tokenHashAndPolicyFallbacksLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/guard/rulebreak/RuleBreakEvaluator.java"));

        assertTrue(source.contains("traceSuppressed(\"ruleBreak.tokenHash\", e);"));
        assertTrue(source.contains("traceSuppressed(\"ruleBreak.policy\", ex);"));
        assertTrue(source.contains("TraceStore.put(\"rulebreak.suppressed.\" + safeStage, true);"));
    }

    private static RuleBreakEvaluator evaluator(String token) {
        RuleBreakEvaluator evaluator = new RuleBreakEvaluator();
        ReflectionTestUtils.setField(evaluator, "adminToken", token);
        ReflectionTestUtils.setField(evaluator, "defaultTtl", 60);
        return evaluator;
    }
}
