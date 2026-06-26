package com.example.lms.service.guard;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveTopicDetectorTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void marksSensitiveTopicWithTagOnlyTrace() {
        SensitiveTopicDetector detector = new SensitiveTopicDetector();
        ReflectionTestUtils.setField(detector, "enabled", true);
        GuardContext guardContext = new GuardContext();
        ChatRequestDto request = ChatRequestDto.builder()
                .message("PTSD contact user.name@example.com api_key=raw-api-value")
                .build();

        detector.applyTo(guardContext, request);

        assertTrue(guardContext.isSensitiveTopic());
        assertEquals(Boolean.TRUE, guardContext.getPlanOverride("memory.forceOff"));
        assertEquals(Boolean.TRUE, guardContext.getPlanOverride("privacy.boundary.mask-web-query"));
        assertEquals(Boolean.TRUE, TraceStore.get("privacy.sensitive"));
        assertEquals("[trauma]", String.valueOf(TraceStore.get("privacy.sensitive.tags")));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("user.name@example.com"), trace);
        assertFalse(trace.contains("raw-api-value"), trace);
    }

    @Test
    void sensitiveTopicDetectorDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/guard/SensitiveTopicDetector.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Sensitive topic fail-soft trace writes need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }
}
