package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApiSecurityExceptionAdviceTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
        SecurityContextHolder.clearContext();
        TraceStore.clear();
    }

    @Test
    void securityErrorBodyReturnsTraceHashInsteadOfRawTraceId() {
        String rawTrace = "sk-" + "apiSecurityTrace012345678901234567";
        MDC.put("traceId", rawTrace);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/private/path");
        ApiSecurityExceptionAdvice advice = new ApiSecurityExceptionAdvice();

        ResponseEntity<Map<String, Object>> response = advice.handleAuthMissing(
                new AuthenticationCredentialsNotFoundException("missing credentials"),
                request);

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertFalse(body.containsValue(rawTrace), String.valueOf(body));
        assertFalse(body.containsKey("trace"), String.valueOf(body));
        assertEquals(SafeRedactor.hashValue(rawTrace), body.get("traceHash"));
    }

    @Test
    void authLookupFailureFallsBackToUnauthorizedWithTraceBreadcrumb() {
        String raw = "ownerToken=secret-auth-context";
        SecurityContextHolder.setContext(new SecurityContext() {
            @Override
            public Authentication getAuthentication() {
                throw new IllegalStateException(raw);
            }

            @Override
            public void setAuthentication(Authentication authentication) {
            }
        });

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/private/path");
        ApiSecurityExceptionAdvice advice = new ApiSecurityExceptionAdvice();

        ResponseEntity<Map<String, Object>> response = advice.handleAccessDenied(
                new AccessDeniedException("denied"),
                request);

        assertEquals(401, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, TraceStore.get("api.security.suppressed.auth_lookup"));
        assertEquals("IllegalStateException", TraceStore.get("api.security.suppressed.auth_lookup.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
    }
}
