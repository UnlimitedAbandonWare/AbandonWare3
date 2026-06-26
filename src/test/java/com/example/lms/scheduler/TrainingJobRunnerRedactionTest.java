package com.example.lms.scheduler;

import com.example.lms.learning.gemini.GeminiClient;
import com.example.lms.moe.RgbMoeProperties;
import com.example.lms.moe.RgbResourceProbe;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrainingJobRunnerRedactionTest {

    @Test
    void blueDebugPreviewAndMessageMaskSecretLikeValues() throws Exception {
        String raw = "api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + " Authorization: " + "Bearer " + "raw-owner-token-123456";

        String preview = invokeString("limit", new Class<?>[]{String.class, int.class}, raw, 1000);
        String message = invokeString("safeMsg", new Class<?>[]{Throwable.class}, new RuntimeException(raw));

        assertRedacted(preview);
        assertRedacted(message);
    }

    @Test
    void blueFailureTraceToleratesMissingRetryAfterAndRequestId() throws Exception {
        RgbMoeProperties props = new RgbMoeProperties();
        RgbResourceProbe resourceProbe = mock(RgbResourceProbe.class);
        TrainingJobRunner runner = new TrainingJobRunner(
                props,
                mock(UserIdleDetector.class),
                mock(com.example.lms.moe.RgbLogSignalParser.class),
                resourceProbe,
                mock(com.example.lms.moe.RgbStrategySelector.class),
                mock(com.example.lms.moe.RgbSoakReportService.class),
                mock(AutoEvolveDebugStore.class),
                mock(dev.langchain4j.model.chat.ChatModel.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        GeminiClient gemini = mock(GeminiClient.class);
        WebClientResponseException rateLimited = WebClientResponseException.create(
                429,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                "rate limited".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(gemini.keywordVariantsWithMeta(anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenThrow(rateLimited);

        Object result = invoke(
                runner,
                "expandWithBlueDetailed",
                new Class<?>[]{GeminiClient.class, List.class, int.class},
                gemini,
                List.of("base query"),
                2);

        assertNotNull(result);
        Method debug = result.getClass().getDeclaredMethod("debug");
        debug.setAccessible(true);
        AutoEvolveRunDebug.BlueCallDebug blue = (AutoEvolveRunDebug.BlueCallDebug) debug.invoke(result);
        assertNotNull(blue);
        assertEquals(429, blue.httpStatus());
        assertEquals(0, blue.outCount());
        verify(resourceProbe).markBlueCalled();
    }

    private static void assertRedacted(String value) {
        assertNotNull(value);
        assertFalse(value.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""), value);
        assertFalse(value.contains("raw-owner-token"), value);
    }

    private static String invokeString(String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = TrainingJobRunner.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return (String) method.invoke(null, args);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = TrainingJobRunner.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
