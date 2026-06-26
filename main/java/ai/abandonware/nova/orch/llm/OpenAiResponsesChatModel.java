package ai.abandonware.nova.orch.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.llm.OpenAiCompatBaseUrl;
import com.example.lms.llm.OpenAiEndpointCompatibility;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Adapter ChatModel that calls OpenAI Responses API (/v1/responses).
 *
 * <p>Used as a fail-soft route when the requested model is not compatible with /v1/chat/completions.</p>
 */
public final class OpenAiResponsesChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesChatModel.class);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String modelName;
    private final long timeoutMs;
    private final String responsesUrl;
    private final boolean apiKeyPresent;

    public OpenAiResponsesChatModel(String baseUrl, String apiKey, String modelName, long timeoutMs) {
        this.modelName = (modelName == null) ? "" : modelName;
        this.timeoutMs = timeoutMs;
        this.responsesUrl = OpenAiCompatBaseUrl.sanitize(baseUrl) + "/responses";
        String normalizedApiKey = ConfigValueGuards.isMissing(apiKey) ? null : apiKey.trim();
        this.apiKeyPresent = normalizedApiKey != null;

        this.mapper = new ObjectMapper();
        WebClient.Builder builder = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (normalizedApiKey != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + normalizedApiKey);
        }
        this.client = builder.build();
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        if (!apiKeyPresent) {
            String msg = ModelGuardSupport.buildExpectedFailureMessage(modelName, "/v1/responses",
                    "ROUTE_RESPONSES(no_api_key)");
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(msg))
                    .build();
        }

        String input = OpenAiEndpointCompatibility.toCompletionsPrompt(messages);

        Map<String, Object> req = OpenAiEndpointCompatibility.responsesPayload(modelName, input, null, null);

        try {
            String json = client.post()
                    .uri(responsesUrl)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1_000L, timeoutMs)))
                    .block();

            String out = extractAssistantText(json);
            if (!StringUtils.hasText(out)) {
                TraceStore.put("llm.responses.parse.emptyOutput", true);
                out = "(empty responses output)";
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(out))
                    .build();

        } catch (WebClientResponseException wcre) {
            log.warn("OpenAI /responses failed: status={} modelHash={}",
                    wcre.getRawStatusCode(), com.example.lms.trace.SafeRedactor.hashValue(modelName));
            String msg = ModelGuardSupport.buildExpectedFailureMessage(modelName, "/v1/responses", "ROUTE_RESPONSES")
                    + "httpStatus: " + wcre.getRawStatusCode() + "\n";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(msg))
                    .build();

        } catch (Exception e) {
            log.warn("OpenAI /responses failed: modelHash={} failureReason={} errorType={}",
                    com.example.lms.trace.SafeRedactor.hashValue(modelName),
                    "responses-route-error",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"));
            String msg = ModelGuardSupport.buildExpectedFailureMessage(modelName, "/v1/responses", "ROUTE_RESPONSES")
                    + "error: responses-route-error\n";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(msg))
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantText(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            traceSuppressed("llm.responses.parse.invalid", null);
            traceInvalidResponsesJson(json);
            return null;
        }
        try {
            Map<String, Object> m = mapper.readValue(json, MAP);

            // 1) Some variants return output_text directly
            Object ot = m.get("output_text");
            if (ot instanceof String s && StringUtils.hasText(s)) {
                TraceStore.put("responsesModel.format", "RESPONSES_API");
                return s;
            }

            // 2) Common shape: output[] -> {type:"message", content:[{type:"output_text", text:"..."}]}
            Object out = m.get("output");
            if (out instanceof List<?> outList) {
                StringBuilder sb = new StringBuilder();
                for (Object o : outList) {
                    if (!(o instanceof Map<?, ?> mm)) {
                        continue;
                    }
                    Object type = mm.get("type");
                    if (!("message".equals(type))) {
                        continue;
                    }
                    Object content = mm.get("content");
                    if (!(content instanceof List<?> contentList)) {
                        continue;
                    }
                    for (Object c : contentList) {
                        if (!(c instanceof Map<?, ?> cm)) {
                            continue;
                        }
                        Object cType = cm.get("type");
                        if (!("output_text".equals(cType))) {
                            continue;
                        }
                        Object text = cm.get("text");
                        if (text instanceof String ts && StringUtils.hasText(ts)) {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append(ts);
                        }
                    }
                }
                if (sb.length() > 0) {
                    TraceStore.put("responsesModel.format", "RESPONSES_API");
                    return sb.toString();
                }
            }

            Object choices = m.get("choices");
            if (choices instanceof List<?> choiceList) {
                for (Object choice : choiceList) {
                    if (!(choice instanceof Map<?, ?> choiceMap)) {
                        continue;
                    }
                    Object message = choiceMap.get("message");
                    if (!(message instanceof Map<?, ?> messageMap)) {
                        continue;
                    }
                    Object content = messageMap.get("content");
                    if (content instanceof String s && StringUtils.hasText(s)) {
                        TraceStore.put("responsesModel.format", "CHAT_COMPAT");
                        return s;
                    }
                }
            }
            TraceStore.put("responsesModel.format", "UNKNOWN");
            TraceStore.put("responsesModel.parseWarn", "no_text_extracted");
        } catch (Exception ex) {
            TraceStore.put("responsesModel.parseWarn", "responses_format_failed:"
                    + SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            traceSuppressed("llm.responses.parse.invalid", ex);
            traceInvalidResponsesJson(json);
        }
        return null;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        try {
            TraceStore.put(stage + ".suppressed", true);
            TraceStore.put(stage + ".errorType", failure == null ? "unknown"
                    : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown"));
        } catch (RuntimeException traceFailure) {
            log.debug("OpenAI /responses suppressed trace skipped stage={} errorType={}",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                    SafeRedactor.traceLabelOrFallback(traceFailure.getClass().getSimpleName(), "unknown"));
        }
    }

    private static void traceInvalidResponsesJson(String json) {
        TraceStore.put("llm.responses.parse.failed", true);
        TraceStore.put("llm.responses.parse.reason", "invalid_response_json");
        TraceStore.put("llm.responses.parse.bodyPresent", StringUtils.hasText(json));
        TraceStore.put("llm.responses.parse.bodyHash", SafeRedactor.hashValue(json));
        TraceStore.put("llm.responses.parse.bodyLength", json == null ? 0 : json.length());
        log.debug("OpenAI /responses parse skipped: reason={} bodyHash={}",
                "invalid_response_json",
                SafeRedactor.hashValue(json));
    }

    @Override
    public String toString() {
        return "OpenAiResponsesChatModel(modelHash=" + com.example.lms.trace.SafeRedactor.hashValue(modelName) + ")";
    }
}
