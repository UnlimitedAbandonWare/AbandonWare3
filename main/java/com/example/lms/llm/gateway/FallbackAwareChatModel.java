package com.example.lms.llm.gateway;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.example.lms.search.TraceStore;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class FallbackAwareChatModel implements ChatModel {

    private final ChatModel primary;
    private final Supplier<ChatModel> fallbackSupplier;
    private final LlmGatewayFailureClassifier classifier;
    private final LlmGatewayBreadcrumbPublisher breadcrumbs;
    private final String primaryKey;
    private final String fallbackKey;

    public FallbackAwareChatModel(
            ChatModel primary,
            Supplier<ChatModel> fallbackSupplier,
            LlmGatewayFailureClassifier classifier,
            LlmGatewayBreadcrumbPublisher breadcrumbs,
            String primaryKey,
            String fallbackKey) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallbackSupplier = fallbackSupplier;
        this.classifier = classifier == null ? new LlmGatewayFailureClassifier() : classifier;
        this.breadcrumbs = breadcrumbs;
        this.primaryKey = primaryKey;
        this.fallbackKey = fallbackKey;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        try {
            return primary.chat(messages);
        } catch (RuntimeException ex) {
            LlmFailureClass failureClass = classifier.classify(ex);
            TraceStore.put("llm.gateway.fallbackAware.primaryFailure", failureClass.name());
            TraceStore.put("llm.gateway.fallbackAware.sameRequestRetry",
                    fallbackSupplier != null && sameRequestFallbackAllowed(failureClass));
            if (breadcrumbs != null) {
                breadcrumbs.publishFailure(primaryKey, failureClass, ex);
            }
            if (fallbackSupplier == null || !sameRequestFallbackAllowed(failureClass)) {
                throw ex;
            }
            if (breadcrumbs != null) {
                breadcrumbs.publishFallback(primaryKey, fallbackKey, failureClass, "same_request_retry_once");
            }
            ChatModel fallback = fallbackSupplier.get();
            if (fallback == null) {
                throw ex;
            }
            return fallback.chat(messages);
        }
    }

    private static boolean sameRequestFallbackAllowed(LlmFailureClass failureClass) {
        return failureClass != LlmFailureClass.CANCELLED_NEUTRAL;
    }
}
