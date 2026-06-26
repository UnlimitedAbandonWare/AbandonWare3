package com.example.lms.telemetry;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MlaBreadcrumbTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void llmRewardKeepsReportedArmWhenTraceStoreHasStaleArm() {
        TraceStore.put("llm.router.arm", "stale-arm");

        MlaBreadcrumb.appendLlmReward("fresh-arm", true, 25L, "none");

        Object rowObject = TraceStore.get("mla.breadcrumb.llm.reward.fresh-arm");
        assertTrue(rowObject instanceof Map<?, ?>);
        Map<?, ?> row = (Map<?, ?>) rowObject;
        Object dataObject = row.get("data");
        assertTrue(dataObject instanceof Map<?, ?>);
        Map<?, ?> data = (Map<?, ?>) dataObject;
        assertEquals("fresh-arm", data.get("llmArm"));
        assertEquals("llm_router_reward", data.get("stage"));
        assertEquals(1.0d, data.get("relevance"));
        assertEquals("success", data.get("routeDecision"));
        assertEquals("llm_router_reward", TraceStore.get("cihRag.breadcrumb.stage"));
        assertEquals(1.0d, TraceStore.get("cihRag.breadcrumb.relevance"));
        assertEquals("success", TraceStore.get("cihRag.breadcrumb.routeDecision"));
    }

    @Test
    void sseEventAlsoPublishesPerStepBreadcrumbWithoutRawPayload() {
        String rawPayload = "private stream payload api_key=test-secret";

        MlaBreadcrumb.appendSseEvent("chunk", rawPayload);

        Object rowObject = TraceStore.get("mla.breadcrumb.step.chunk");
        assertTrue(rowObject instanceof Map<?, ?>);
        Map<?, ?> row = (Map<?, ?>) rowObject;
        Object dataObject = row.get("data");
        assertTrue(dataObject instanceof Map<?, ?>);
        Map<?, ?> data = (Map<?, ?>) dataObject;
        assertEquals("chunk", data.get("eventType"));
        assertEquals("chunk", data.get("stage"));
        assertEquals(0.0d, data.get("relevance"));
        assertEquals("sse_emit", data.get("routeDecision"));
        assertTrue(String.valueOf(data.get("payloadHash")).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("cihRag.breadcrumb.queryRedacted"));
        assertEquals("chunk", TraceStore.get("cihRag.breadcrumb.stage"));
        assertEquals(0.0d, TraceStore.get("cihRag.breadcrumb.relevance"));
        assertEquals("sse_emit", TraceStore.get("cihRag.breadcrumb.routeDecision"));
        assertTrue(!String.valueOf(row).contains(rawPayload), String.valueOf(row));
        assertTrue(!String.valueOf(row).contains("test-secret"), String.valueOf(row));
    }

    @Test
    void sseEventNullPayloadDoesNotInventNullHash() {
        MlaBreadcrumb.appendSseEvent("heartbeat", null);

        Object rowObject = TraceStore.get("mla.breadcrumb.step.heartbeat");
        assertTrue(rowObject instanceof Map<?, ?>);
        Map<?, ?> row = (Map<?, ?>) rowObject;
        Object dataObject = row.get("data");
        assertTrue(dataObject instanceof Map<?, ?>);
        Map<?, ?> data = (Map<?, ?>) dataObject;
        assertEquals("heartbeat", data.get("eventType"));
        assertEquals(false, data.get("payloadPresent"));
        assertEquals(0, data.get("payloadLength"));
        assertFalse(data.containsKey("payloadHash"));
    }

    @Test
    void malformedNumericTraceFieldsRecordInvalidNumberWithoutRawLeak() {
        String raw = "ownerToken=raw-secret";
        TraceStore.put("cfvm.jb.score", raw);

        MlaBreadcrumb.appendSseEvent("chunk", null);

        assertEquals(Boolean.TRUE, TraceStore.get("mla.breadcrumb.suppressed.toDouble"));
        assertEquals("invalid_number", TraceStore.get("mla.breadcrumb.suppressed.toDouble.errorType"));
        assertEquals("toDouble", TraceStore.get("mla.breadcrumb.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("mla.breadcrumb.suppressed.errorType"));
        String row = String.valueOf(TraceStore.get("mla.breadcrumb.step.chunk"));
        assertFalse(row.contains(raw), row);
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
    }
}
