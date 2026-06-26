package com.example.lms.service;

import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NaverSearchServiceInterruptContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void interruptHelperRestoresInterruptedFlag() {
        Thread.interrupted();
        try {
            NaverSearchService.restoreInterruptFlag();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void naverInterruptedExceptionPathsDoNotClearInterruptFlag() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("Thread.interrupted();"));
        assertTrue(source.contains("Thread.currentThread().interrupt();"));
    }

    @Test
    void cancellationExceptionEmitsCancelledTraceWithoutRawQueryOrToken() {
        TraceStore.clear();
        String rawQuery = "private naver cancelled query";
        String rawSecret = "secret-ownerToken-value";
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(
                        new IllegalStateException("wrapped provider cancellation",
                                new CancellationException("cancelled ownerToken=" + rawSecret + " query=" + rawQuery))))
                .build();
        NaverSearchService service = naverService(webClient, "id:" + rawSecret);

        var out = service.searchSnippetsMono(rawQuery, 3).block(Duration.ofSeconds(3));

        assertTrue(out == null || out.isEmpty());
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.timeout"));
        assertEquals("cancelled", TraceStore.get("web.naver.failureReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.cancelled"));
        assertEquals("cancelled", TraceStore.get("web.naver.exceptionType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains(rawSecret), trace);
        assertFalse(trace.contains("CancellationException"), trace);
        assertFalse(trace.contains("ownerToken"), trace);
    }

    @SuppressWarnings("unchecked")
    private static NaverSearchService naverService(WebClient webClient, String naverKeys) {
        RateLimitPolicy ratePolicy = mock(RateLimitPolicy.class);
        when(ratePolicy.allowedExpansions()).thenReturn(1);
        when(ratePolicy.currentDelayMs()).thenReturn(0L);

        GuardProfileProps guardProfileProps = mock(GuardProfileProps.class);
        when(guardProfileProps.currentProfile()).thenReturn(GuardProfile.PROFILE_FREE);

        NaverSearchService service = new NaverSearchService(
                mock(QueryTransformer.class),
                mock(MemoryReinforcementService.class),
                mock(ObjectProvider.class),
                mock(EmbeddingStore.class),
                mock(EmbeddingModel.class),
                (Supplier<Long>) () -> null,
                null,
                naverKeys,
                "",
                "",
                16L,
                30L,
                mock(PlatformTransactionManager.class),
                ratePolicy,
                webClient,
                mock(ObjectProvider.class),
                null);
        ReflectionTestUtils.setField(service, "guardProfileProps", guardProfileProps);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "queryTransformTimeoutMs", 500L);
        ReflectionTestUtils.setField(service, "similarThreshold", 0.86d);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        ReflectionTestUtils.setField(service, "fusionPolicy", "none");
        return service;
    }
}
