package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Ollama-native ChatModel for qwen thinking models.
 *
 * <p>LangChain4j 1.0.1's OpenAI-compatible builder cannot add Ollama's
 * native {@code think=false} request flag. This adapter is intentionally
 * narrow: it is selected only by {@link DynamicChatModelFactory} for loopback
 * qwen thinking models where blank content has been observed.</p>
 */
public final class OllamaNativeChatModel implements ChatModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String chatUrl;
    private final String modelName;
    private final Duration timeout;
    private final Integer maxTokens;
    private final Double temperature;

    public OllamaNativeChatModel(String openAiCompatBaseUrl,
                                 String modelName,
                                 Duration timeout,
                                 Integer maxTokens,
                                 Double temperature) {
        this.client = WebClient.builder().build();
        this.chatUrl = nativeChatUrl(openAiCompatBaseUrl);
        this.modelName = modelName == null ? "" : modelName.trim();
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        String prompt = OpenAiEndpointCompatibility.toCompletionsPrompt(messages);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", modelName);
        payload.put("stream", false);
        payload.put("think", false);
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        Map<String, Object> options = new LinkedHashMap<>();
        if (maxTokens != null && maxTokens > 0) {
            options.put("num_predict", maxTokens);
        }
        if (temperature != null) {
            options.put("temperature", temperature);
        }
        if (!options.isEmpty()) {
            payload.put("options", options);
        }

        traceRequest(prompt);
        try {
            String body = client.post()
                    .uri(chatUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block();
            String text = extractText(body);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        } catch (RuntimeException ex) {
            traceSuppressed("llm.ollamaNative.call", ex);
            throw ex;
        }
    }

    private String extractText(String body) {
        if (body == null || body.isBlank()) {
            TraceStore.put("llm.ollamaNative.emptyBody", true);
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            String text = root.path("message").path("content").asText("");
            String thinking = root.path("message").path("thinking").asText("");
            String doneReason = root.path("done_reason").asText("");
            TraceStore.put("llm.ollamaNative.contentLength", text.length());
            TraceStore.put("llm.ollamaNative.thinkingLength", thinking.length());
            if (!doneReason.isBlank()) {
                TraceStore.put("llm.ollamaNative.doneReason",
                        SafeRedactor.traceLabelOrFallback(doneReason, "unknown"));
            }
            return text;
        } catch (Exception ex) {
            traceSuppressed("llm.ollamaNative.parse", ex);
            return "";
        }
    }

    private void traceRequest(String prompt) {
        TraceStore.put("llm.ollamaNative.route", true);
        TraceStore.put("llm.ollamaNative.thinkDisabled", true);
        TraceStore.put("llm.ollamaNative.modelHash", SafeRedactor.hashValue(modelName));
        TraceStore.put("llm.ollamaNative.modelLength", modelName.length());
        TraceStore.put("llm.ollamaNative.promptHash", SafeRedactor.hashValue(prompt));
        TraceStore.put("llm.ollamaNative.promptLength", prompt == null ? 0 : prompt.length());
        TraceStore.put("llm.ollamaNative.endpointHost", LocalLlmGatewaySecurity.endpointHost(chatUrl));
    }

    private static void traceSuppressed(String stage, Throwable ex) {
        TraceStore.put(stage + ".suppressed", true);
        TraceStore.put(stage + ".errorType", ex == null ? "unknown"
                : SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
    }

    private static String nativeChatUrl(String openAiCompatBaseUrl) {
        String url = OpenAiCompatBaseUrl.sanitize(openAiCompatBaseUrl);
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url + "/api/chat";
    }
}
