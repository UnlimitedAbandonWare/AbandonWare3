package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoEvidenceChatFallbackTest {

    @Test
    void greetingFallbackAnswersInsteadOfAskingForEvidence() {
        String answer = NoEvidenceChatFallback.compose("안녕. 한 문장으로 인사해줘.");

        assertTrue(answer.contains("안녕하세요"));
        assertTrue(answer.contains("로컬 안전 폴백"));
        assertTrue(answer.contains("먼저"));
        assertTrue(answer.contains("도와"));
        assertFalse(answer.toLowerCase(Locale.ROOT).contains("evidence"));
        assertFalse(answer.contains("근거를 다시"));
    }

    @Test
    void genericFallbackIsTransparentAndNonBlank() {
        String answer = NoEvidenceChatFallback.compose("오늘 대화 가능한 상태인지 알려줘");

        assertTrue(answer.contains("로컬 안전 폴백"));
        assertTrue(answer.contains("질문은 접수"));
        assertTrue(answer.contains("새 요청"));
        assertTrue(answer.length() > 40);
        assertFalse(answer.toLowerCase(Locale.ROOT).contains("evidence-only"));
    }

    @Test
    void domainTermsStartingWithHiAreNotTreatedAsGreetings() {
        String answer = NoEvidenceChatFallback.compose("하이퍼네크 불확정성 원리가 뭐냐?");

        assertTrue(answer.contains("질문은 접수"));
        assertFalse(answer.contains("무엇을 도와드릴까요?"));
    }

    @Test
    void noEvidencePredicateRequiresAllRetrievalListsToBeEmpty() {
        assertTrue(NoEvidenceChatFallback.hasNoEvidence(null, List.of()));
        assertFalse(NoEvidenceChatFallback.hasNoEvidence(List.of("doc"), List.of()));
    }
}
