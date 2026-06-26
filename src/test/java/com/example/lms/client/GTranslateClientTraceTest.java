package com.example.lms.client;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GTranslateClientTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void missingKeysLeaveProviderDisabledTraceWithoutOutboundCall() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.error(new AssertionError("unexpected outbound call"));
                })
                .build();
        GTranslateClient client = new GTranslateClient(webClient);
        ReflectionTestUtils.setField(client, "apiKeys", List.of("changeme", "<YOUR_API_KEY>"));

        String translated = client.translate("hello", "en", "ko").block();

        assertEquals(null, translated);
        assertEquals(0, calls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("googleTranslate.providerDisabled"));
        assertEquals("missing_google_translate_key", TraceStore.get("googleTranslate.disabledReason"));
    }

    @Test
    void callFailureLeavesRedactedTraceBreadcrumb() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new NumberFormatException("ownerToken=translate-secret")))
                .build();
        GTranslateClient client = new GTranslateClient(webClient);
        ReflectionTestUtils.setField(client, "apiKeys", List.of("unit-key-123"));

        String translated = client.translate("hello", "en", "ko").block();

        assertEquals(null, translated);
        assertEquals(Boolean.TRUE, TraceStore.get("googleTranslate.suppressed"));
        assertEquals("translate.call", TraceStore.get("googleTranslate.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("googleTranslate.suppressed.errorType"));
        assertTrue(String.valueOf(TraceStore.get("googleTranslate.suppressed.messageHash")).startsWith("hash:"));
        assertEquals("ownerToken=translate-secret".length(),
                TraceStore.get("googleTranslate.suppressed.messageLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=translate-secret"));
    }
}
