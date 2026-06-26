package com.example.lms.llm.gateway;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FallbackAwareChatModelTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void retriesOnceOnFallbackRoute() {
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw new RuntimeException("model not found");
            }
        };
        ChatModel fallback = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("fallback ok"))
                        .build();
            }
        };
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                primary,
                () -> fallback,
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3");

        ChatResponse response = model.chat(List.<ChatMessage>of());

        assertEquals("fallback ok", response.aiMessage().text());
        assertEquals("MODEL_MISSING", TraceStore.get("llm.gateway.fallbackAware.primaryFailure"));
        assertEquals(true, TraceStore.get("llm.gateway.fallbackAware.sameRequestRetry"));
    }
}
