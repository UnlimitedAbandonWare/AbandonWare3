package com.example.lms.llm.gateway;

import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.SseEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmGatewayBreadcrumbPublisherTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void publisherDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/llm/gateway/LlmGatewayBreadcrumbPublisher.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "LLM gateway breadcrumb publisher needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesRedactedTraceAndSsePayload() {
        SseEventPublisher sse = mock(SseEventPublisher.class);
        ObjectProvider<SseEventPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sse);
        LlmGatewayBreadcrumbPublisher publisher = new LlmGatewayBreadcrumbPublisher(provider);

        publisher.publishEligibility(new RoutingEligibility(
                "api3",
                "groq",
                "qwen/qwen3-32b",
                "chat",
                80,
                true,
                false,
                List.of(),
                Map.of("apiKey", "secret-value", "endpointHost", "api.groq.com")));

        verify(sse).emit(eq("llm.gateway.route"), any());
        assertFalse(TraceStore.getAll().toString().contains("secret-value"));
    }
}
