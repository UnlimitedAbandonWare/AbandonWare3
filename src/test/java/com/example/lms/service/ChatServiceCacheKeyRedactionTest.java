package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceCacheKeyRedactionTest {

    @Test
    void cacheKeyUsesMessageHashInsteadOfRawMessage() {
        String raw = "private chat message should not appear in cache keys";
        ChatRequestDto req = ChatRequestDto.builder()
                .sessionId(7L)
                .model("local-model")
                .message(raw)
                .useRag(Boolean.TRUE)
                .useWebSearch(Boolean.FALSE)
                .build();

        String key = ChatService.cacheKey(req);

        assertFalse(key.contains(raw));
        assertTrue(key.contains(SafeRedactor.hash12(raw)));
    }
}
