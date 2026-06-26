package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.LearningExampleRow;
import com.example.lms.guard.KeyResolver;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Unified Gemini client.
 *
 * <p>Key resolution is centralized via {@link KeyResolver}. BLUE/Gemini is
 * intended for offline or idle jobs, not request-path learning writes.</p>
 */
@Component("geminiClient")
public class GeminiClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final WebClient.Builder webClientBuilder;
    private final KeyResolver keyResolver;

    public GeminiClient(WebClient.Builder webClientBuilder, KeyResolver keyResolver) {
        this.webClientBuilder = webClientBuilder;
        this.keyResolver = keyResolver;
    }

    public Mono<String> translate(String text, String srcLang, String tgtLang) {
        String translationPrompt = "Translate the following text from %s to %s: %s"
                .formatted(srcLang, tgtLang, text);
        return postToGemini(translationPrompt)
                .map(r -> r.candidates().get(0).content().parts().get(0).text())
                .doOnSubscribe(s -> log.debug("Gemini translate srcLang={} tgtLang={}", srcLang, tgtLang))
                .doOnError(e -> log.error("[Gemini] translate API failed. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(e)), messageLength(e)))
                .onErrorResume(e -> Mono.just("[translation failed] " + safeFallbackText(text)));
    }

    public Mono<String> generate(String prompt) {
        return postToGemini(prompt)
                .map(this::toPrettyJson)
                .doOnSubscribe(s -> log.debug("Gemini generate promptHash={} promptLength={}",
                        SafeRedactor.hashValue(prompt), prompt == null ? 0 : prompt.length()))
                .doOnError(e -> log.error("[Gemini] generate API failed. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(e)), messageLength(e)))
                .onErrorResume(e -> Mono.just("""
                        {
                          "ok"   : false,
                          "errorHash": "%s",
                          "errorLength": %d
                        }""".formatted(SafeRedactor.hashValue(messageOf(e)), messageLength(e))));
    }

    /**
     * Best-effort query variant helper. Caller should enforce quota/cooldown.
     */
    public List<String> keywordVariants(String cleaned, String anchor, int cap) {
        KeywordVariantsResult r = keywordVariantsWithMeta(cleaned, anchor, cap, Duration.ofSeconds(12));
        return r == null ? Collections.emptyList() : r.variants();
    }

    /**
     * Best-effort query variant helper with HTTP status and headers for ops/debug.
     */
    public KeywordVariantsResult keywordVariantsWithMeta(String cleaned, String anchor, int cap, Duration timeout) {
        int n = Math.max(0, cap);
        if (n <= 0) return new KeywordVariantsResult(Collections.emptyList(), null, HttpHeaders.EMPTY);

        String key = keyResolver.resolveGeminiApiKeyStrict();
        if (key == null || key.isBlank()) {
            return new KeywordVariantsResult(Collections.emptyList(), null, HttpHeaders.EMPTY);
        }

        String q = (cleaned == null || cleaned.isBlank()) ? (anchor == null ? "" : anchor) : cleaned;
        String keywordVariantPrompt = """
                You are a search query expansion helper.
                Return only expanded queries in Korean, one per line, no numbering.
                Base query: %s
                Generate up to %d alternative queries.
                """.formatted(q, n);

        Duration t = timeout == null ? Duration.ofSeconds(12) : timeout;

        ResponseEntity<GeminiResponse> entity;
        try {
            entity = postToGeminiEntity(keywordVariantPrompt)
                    .timeout(t)
                    .block();
        } catch (RuntimeException e) {
            traceKeywordVariantSuppressed(e);
            return new KeywordVariantsResult(Collections.emptyList(), null, HttpHeaders.EMPTY);
        }

        if (entity == null) {
            return new KeywordVariantsResult(Collections.emptyList(), null, HttpHeaders.EMPTY);
        }

        GeminiResponse resp = entity.getBody();
        if (resp == null || resp.candidates() == null || resp.candidates().isEmpty()) {
            return new KeywordVariantsResult(Collections.emptyList(), entity.getStatusCodeValue(), entity.getHeaders());
        }

        String raw = resp.candidates().get(0).content().parts().get(0).text();
        if (raw == null || raw.isBlank()) {
            return new KeywordVariantsResult(Collections.emptyList(), entity.getStatusCodeValue(), entity.getHeaders());
        }

        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            if (line == null) continue;
            String s = line.trim();
            if (s.isEmpty()) continue;
            s = s.replaceAll("^[0-9]+[).:-]\\s*", "");
            if (s.length() > 200) s = s.substring(0, 200);
            if (!s.isBlank()) uniq.add(s);
            if (uniq.size() >= n) break;
        }

        return new KeywordVariantsResult(new ArrayList<>(uniq), entity.getStatusCodeValue(), entity.getHeaders());
    }

    private Mono<GeminiResponse> postToGemini(String prompt) {
        String apiKey = keyResolver.resolveGeminiApiKeyStrict();
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new IllegalStateException("Gemini API key missing"));
        }

        WebClient client = webClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
        String url = "/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;
        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                )
        );
        return client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(GeminiResponse.class);
    }

    private Mono<ResponseEntity<GeminiResponse>> postToGeminiEntity(String prompt) {
        String apiKey = keyResolver.resolveGeminiApiKeyStrict();
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new IllegalStateException("Gemini API key missing"));
        }

        WebClient client = webClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
        String url = "/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;
        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                )
        );
        return client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(GeminiResponse.class);
    }

    private String toPrettyJson(GeminiResponse r) {
        String text = r.candidates().get(0).content().parts().get(0).text();
        ObjectMapper om = new ObjectMapper();
        ObjectNode node = om.createObjectNode();
        node.put("ok", true);
        node.put("data", text);
        return node.toPrettyString();
    }

    private static String safeFallbackText(String text) {
        String safe = SafeRedactor.safeMessage(text, 4_000);
        return safe == null ? "" : safe;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static void traceKeywordVariantSuppressed(Throwable failure) {
        String message = messageOf(failure);
        TraceStore.put("gemini.suppressed.stage", "keywordVariants");
        TraceStore.put("gemini.suppressed.errorType",
                failure == null ? "unknown"
                        : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown"));
        TraceStore.put("gemini.suppressed.messageHash", SafeRedactor.hashValue(message));
        TraceStore.put("gemini.suppressed.messageLength", message == null ? 0 : message.length());
    }

    public KnowledgeDelta curate(LearningEvent event, String model, Duration timeout) {
        return new KnowledgeDelta(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public List<LearningExampleRow> batchNormalize(List<LearningEvent> events, String model) {
        return List.of();
    }

    private record Part(String text) {}
    private record Content(List<Part> parts) {}
    private record Candidate(Content content) {}
    private record GeminiResponse(List<Candidate> candidates) {}

    /**
     * Keyword variants response wrapper containing status and headers.
     *
     * <p>Headers are returned as-is; callers should whitelist before persisting.</p>
     */
    public record KeywordVariantsResult(List<String> variants, Integer httpStatus, HttpHeaders headers) {}
}
