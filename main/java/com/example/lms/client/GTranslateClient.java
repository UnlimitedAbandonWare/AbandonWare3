package com.example.lms.client;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Google Translate v2 REST 래퍼
 */
@Component
@RequiredArgsConstructor
public class GTranslateClient {
    private static final Logger log = LoggerFactory.getLogger(GTranslateClient.class);

    /** WebClientConfig 에서 만든 @Bean(name="googleTranslateWebClient") */
    @Qualifier("googleTranslateWebClient")
    private final WebClient webClient;

    /** application.properties 에 comma 로 나열된 여러 API Key */
    @Value("${google.translate.keys:}")
    private List<String> apiKeys = List.of();

    private int keyIndex = 0;   // simple round-robin
    private final AtomicBoolean missingKeysLogged = new AtomicBoolean(false);

    public Mono<String> translate(String text, String srcLang, String tgtLang) {

        String apiKey = nextApiKeyOrNull();
        if (apiKey == null) {
            traceProviderDisabled("missing_google_translate_key");
            if (missingKeysLogged.compareAndSet(false, true)) {
                log.warn("[AWX][boot][placeholder] provider=googleTranslate enabled=false disabledReason=missing_google_translate_key");
            }
            return Mono.empty();
        }
        String url = "/language/translate/v2?key=" + apiKey;

        Map<String, Object> body = Map.of(
                "q",      text,
                "source", srcLang,
                "target", tgtLang,
                "format", "text"
        );

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(GoogleTranslateResponse.class)
                .map(r -> r.data().translations().get(0).translatedText())
                .doOnError(e -> {
                    traceSuppressed(e);
                    log.error("[GTranslate] API call failed. errorHash={} errorLength={}",
                            SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                })
                .onErrorResume(e -> Mono.empty());   // 실패 시 Gemini 로 폴백
    }

    /* ▼ (GSON/Jackson record 매핑용) */
    private synchronized String nextApiKeyOrNull() {
        List<String> usable = usableKeys(apiKeys);
        if (usable.isEmpty()) {
            return null;
        }
        return usable.get(Math.floorMod(keyIndex++, usable.size()));
    }

    private static List<String> usableKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(k -> !ConfigValueGuards.isMissing(k))
                .toList();
    }

    private static void traceProviderDisabled(String reason) {
        try {
            TraceStore.put("googleTranslate.providerDisabled", true);
            TraceStore.put("googleTranslate.disabledReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (RuntimeException traceFailure) {
            log.debug("[GTranslate] provider-disabled trace failed errorType={}", errorType(traceFailure));
        }
    }

    private static void traceSuppressed(Throwable failure) {
        try {
            TraceStore.put("googleTranslate.suppressed", true);
            TraceStore.put("googleTranslate.suppressed.stage", "translate.call");
            TraceStore.put("googleTranslate.suppressed.errorType", errorType(failure));
            TraceStore.put("googleTranslate.suppressed.messageHash", SafeRedactor.hashValue(messageOf(failure)));
            TraceStore.put("googleTranslate.suppressed.messageLength", messageLength(failure));
        } catch (RuntimeException traceFailure) {
            log.debug("[GTranslate] suppressed trace failed errorType={}", errorType(traceFailure));
        }
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private record Translation(String translatedText) {}
    private record TranslationData(List<Translation> translations) {}
    private record GoogleTranslateResponse(TranslationData data) {}
}
