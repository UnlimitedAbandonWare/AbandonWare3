package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.lms.search.TraceStore;
import com.example.lms.translation.ApiKeyManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class TranslationServiceRedactionContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void missingApiKeyReturnsOriginalTextWithDisabledTrace() {
        TranslationService service = new TranslationService(new ApiKeyManager(List.of()));

        String translated = service.koToEn("민감하지 않은 번역 샘플");

        assertEquals("민감하지 않은 번역 샘플", translated);
        assertEquals(Boolean.TRUE, TraceStore.get("translation.disabled"));
        assertEquals("api-key-missing", TraceStore.get("translation.disabledReason"));
    }

    @Test
    void providerFailureReturnsOriginalTextWithFailureReasonTrace() {
        TranslationService service = new TranslationService(new ApiKeyManager(List.of("unit-key-123")));
        ReflectionTestUtils.setField(service, "restTemplate", new RestTemplate() {
            @Override
            public <T> T postForObject(String url, Object request, Class<T> responseType, Object... uriVariables)
                    throws RestClientException {
                throw new RestClientException("ownerToken=translate-secret");
            }
        });

        String translated = service.enToKo("translation fallback sample");

        assertEquals("translation fallback sample", translated);
        assertEquals("RestClientException", TraceStore.get("translation.failReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=translate-secret"));
    }

    @Test
    void googleTranslateExceptionMessageDoesNotExposeRawProviderError() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/TranslationService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("Google Translate API(v2) 호출 실패: \" + ex.getMessage()"));
        assertFalse(source.contains("throw new RuntimeException(\"Google Translate API(v2)"));
        assertTrue(source.contains("TraceStore.put(\"translation.failReason\""));
    }

    @Test
    void translationPathLeavesTrainingRagBreadcrumbsWithoutRawText() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/TranslationService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("TraceStore.put(\"translation.source\""));
        assertTrue(source.contains("TraceStore.put(\"translation.ragBound\", \"true\")"));
        assertFalse(source.contains("TraceStore.put(\"translation.text\""));
    }
}
