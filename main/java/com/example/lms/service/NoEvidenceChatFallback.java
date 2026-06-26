package com.example.lms.service;

import java.util.Collection;
import java.util.Locale;

import com.example.lms.search.TraceStore;

final class NoEvidenceChatFallback {

    private static final String LOCAL_FALLBACK_NOTICE =
            "기본 모델 응답이 지연되어 로컬 안전 폴백으로 먼저 안내드립니다.";

    private NoEvidenceChatFallback() {
    }

    static boolean hasNoEvidence(Collection<?>... sources) {
        if (sources == null || sources.length == 0) {
            return true;
        }
        for (Collection<?> source : sources) {
            if (source != null && !source.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static String compose(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            return "요청 내용을 입력해 주세요. " + LOCAL_FALLBACK_NOTICE
                    + " 질문을 보내면 처리 상태와 다음 단계를 함께 표시합니다.";
        }
        if (isGreeting(normalized)) {
            return "안녕하세요. " + LOCAL_FALLBACK_NOTICE
                    + " 무엇을 도와드릴까요?";
        }
        return LOCAL_FALLBACK_NOTICE
                + " 질문은 접수됐지만 현재 근거가 없어 단정 답변은 제한됩니다."
                + " 핵심 조건이나 원하는 출력 형식을 알려주시면 새 요청에서 이어서 도와드릴게요.";
    }

    static ChatResult orEvidenceFallback(
            String query,
            String modelUsed,
            boolean ragUsed,
            Collection<?> topDocs,
            Collection<?> vectorDocs,
            String evidenceFallback) {
        if (hasNoEvidence(topDocs, vectorDocs)) {
            TraceStore.put("chat.llmFallback.mode", "local_lite_no_evidence");
            return ChatResult.of(compose(query), modelUsed + ":fallback:local-lite", false);
        }
        return ChatResult.of(evidenceFallback, modelUsed + ":fallback:evidence", ragUsed);
    }

    private static boolean isGreeting(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(hi|hello|hey)\\b.*")
                || lower.contains("안녕")
                || containsGreetingToken(lower, "하이")
                || containsGreetingToken(lower, "헬로")
                || lower.contains("반가");
    }

    private static boolean containsGreetingToken(String text, String token) {
        int from = 0;
        while (from < text.length()) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return false;
            }
            int before = at - 1;
            int after = at + token.length();
            boolean leftBoundary = before < 0 || !Character.isLetterOrDigit(text.charAt(before));
            boolean rightBoundary = after >= text.length() || !Character.isLetterOrDigit(text.charAt(after));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = at + token.length();
        }
        return false;
    }
}
