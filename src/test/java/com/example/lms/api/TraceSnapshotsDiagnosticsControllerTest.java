package com.example.lms.api;

import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceSnapshotsDiagnosticsControllerTest {

    @Test
    void htmlFallbackDoesNotEchoRawSnapshotIdWhenStoreIsUnavailable() {
        String rawId = "trace-secret-id-12345";
        ObjectProvider<TraceSnapshotStore> provider = mockProvider(null);
        TraceSnapshotsDiagnosticsController controller = new TraceSnapshotsDiagnosticsController(provider);

        ResponseEntity<String> response = controller.getHtml(rawId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        String body = response.getBody();
        assertFalse(body.contains(rawId));
        assertTrue(body.contains("idHash"));
        assertTrue(body.contains(SafeRedactor.hashValue(rawId)));
    }

    @Test
    void htmlFallbackDoesNotEchoRawSnapshotIdWhenSnapshotIsMissing() {
        String rawId = "trace-secret-id-67890";
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        when(store.get(rawId)).thenReturn(Optional.empty());
        ObjectProvider<TraceSnapshotStore> provider = mockProvider(store);
        TraceSnapshotsDiagnosticsController controller = new TraceSnapshotsDiagnosticsController(provider);

        ResponseEntity<String> response = controller.getHtml(rawId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        String body = response.getBody();
        assertFalse(body.contains(rawId));
        assertTrue(body.contains("idHash"));
        assertTrue(body.contains(SafeRedactor.hashValue(rawId)));
    }

    @Test
    void htmlFallbackForSnapshotWithoutStoredHtmlRedactsRawMetadata() {
        String rawId = "trace-raw-id-24680";
        String rawSid = "trace-raw-session-24680";
        String rawTraceId = "trace-raw-trace-24680";
        String rawRequestId = "trace-raw-request-24680";
        String rawPath = "/api/diagnostics/trace/snapshots?token=private-query-value";
        String rawError = "private stack message";
        TraceSnapshotStore.TraceSnapshot snapshot = new TraceSnapshotStore.TraceSnapshot(
                rawId,
                1L,
                "2026-06-05T00:00:00Z",
                rawSid,
                rawSid,
                rawTraceId,
                rawRequestId,
                "unit_test",
                "GET",
                rawPath,
                500,
                rawError,
                false,
                2,
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                false);
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        when(store.get(rawId)).thenReturn(Optional.of(snapshot));
        ObjectProvider<TraceSnapshotStore> provider = mockProvider(store);
        TraceSnapshotsDiagnosticsController controller = new TraceSnapshotsDiagnosticsController(provider);

        ResponseEntity<String> response = controller.getHtml(rawId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertFalse(body.contains(rawId));
        assertFalse(body.contains(rawSid));
        assertFalse(body.contains(rawTraceId));
        assertFalse(body.contains(rawRequestId));
        assertFalse(body.contains(rawPath));
        assertFalse(body.contains(rawError));
        assertTrue(body.contains("idHash"));
        assertTrue(body.contains("sidHash"));
        assertTrue(body.contains("traceIdHash"));
        assertTrue(body.contains("requestIdHash"));
        assertTrue(body.contains("pathHash"));
        assertTrue(body.contains("errorHash"));
        assertTrue(body.contains(SafeRedactor.hashValue(rawId)));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TraceSnapshotStore> mockProvider(TraceSnapshotStore store) {
        ObjectProvider<TraceSnapshotStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }
}
