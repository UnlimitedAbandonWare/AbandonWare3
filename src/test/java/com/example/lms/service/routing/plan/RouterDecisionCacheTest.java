package com.example.lms.service.routing.plan;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterDecisionCacheTest {

    @BeforeEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void supplierFailurePropagatesWithRedactedSuppressionTrace() {
        RouterDecisionCache cache = new RouterDecisionCache("router.plan.cache", false, 10, 60);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> cache.getOrCompute(
                        "tenant:ownerToken=raw-secret",
                        "decision:api_key=raw-secret",
                        "slice-a",
                        String.class,
                        () -> {
                            throw new IllegalStateException("ownerToken=raw-secret");
                        }));

        assertEquals("ownerToken=raw-secret", thrown.getMessage());
        assertEquals(Boolean.TRUE, TraceStore.get("router.plan.cache.suppressed"));
        assertEquals("compute", TraceStore.get("router.plan.cache.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("router.plan.cache.suppressed.errorType"));
        assertTrue(String.valueOf(TraceStore.get("router.plan.cache.suppressed.keyHash")).startsWith("hash:"));
        assertEquals(74, TraceStore.get("router.plan.cache.suppressed.keyLength"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken=raw-secret"));
        assertFalse(trace.contains("api_key=raw-secret"));
    }
}
