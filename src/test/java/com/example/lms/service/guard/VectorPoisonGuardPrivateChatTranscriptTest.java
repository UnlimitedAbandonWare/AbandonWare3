package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorPoisonGuardPrivateChatTranscriptTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void blocksSyntheticPrivateChatTranscriptWithoutLeakingRawAliases() {
        VectorPoisonGuard guard = guard();
        String transcript = """
                [UserA/RegionA] [PM 4:09] synthetic aquarium note
                [UserB/RegionB] [PM 4:10] photo 1
                [UserA/RegionA] [PM 4:11] shrimp tank synthetic note
                [UserB/RegionB] [PM 4:12] store recommendation
                """;

        VectorPoisonGuard.IngestDecision decision =
                guard.inspectIngest("sid-private", transcript, Map.of(), "ingest");

        assertFalse(decision.allow());
        assertEquals("", decision.text());
        assertEquals("private_chat_transcript", decision.reason());
        assertEquals("CHAT_TRANSCRIPT", decision.meta().get(VectorMetaKeys.META_DOC_TYPE));
        assertEquals("private_chat_transcript", decision.meta().get(VectorMetaKeys.META_POISON_REASON));
        assertEquals(4, TraceStore.get("vector.poison.privateChat.lines"));
        assertEquals(4, TraceStore.get("vector.poison.privateChat.headers"));
        assertEquals(2, TraceStore.get("vector.poison.privateChat.domainMarkers"));
        assertEquals("private_chat_transcript", TraceStore.get("vector.poison.privateChat.reason"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("UserA"), trace);
        assertFalse(trace.contains("RegionA"), trace);
        assertFalse(trace.contains("synthetic aquarium note"), trace);
        assertTrue(trace.contains("private_chat_transcript"), trace);
    }

    @Test
    void blocksGeneratedDifficultyReportBeforeVectorIngest() {
        VectorPoisonGuard guard = guard();
        String report = """
                # 소스 난이도 평가 리포트

                > 기준: 비슷한 기능을 갖춘 시스템을 경험 있는 시니어 개발자가 처음부터 새로 만든다면
                > 얼마나 어려운가?

                ## 측정된 수치 요약

                | 지표 | 값 |
                |---|---|
                | Java 파일 | 1,847개 |
                | 총 코드 라인 | 255,612줄 |
                | AOP Aspect | 63개 |

                ### 전체 난이도: 8.3 / 10

                Claude Sonnet generated this source difficulty scorecard from incomplete source context.
                """;

        VectorPoisonGuard.IngestDecision decision =
                guard.inspectIngest("sid-report", report, Map.of(), "download.ingest");

        assertFalse(decision.allow());
        assertEquals("", decision.text());
        assertEquals("generated_report", decision.reason());
        assertEquals("REPORT", decision.meta().get(VectorMetaKeys.META_DOC_TYPE));
        assertEquals("generated_report", decision.meta().get(VectorMetaKeys.META_POISON_REASON));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("소스 난이도 평가 리포트"), trace);
        assertFalse(trace.contains("255,612"), trace);
        assertTrue(trace.contains("generated_report"), trace);
    }

    private static VectorPoisonGuard guard() {
        VectorPoisonGuard guard = new VectorPoisonGuard();
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "blockLogLike", true);
        ReflectionTestUtils.setField(guard, "sanitizeTraceDump", true);
        ReflectionTestUtils.setField(guard, "maxTextChars", 20_000);
        ReflectionTestUtils.setField(guard, "maxLines", 400);
        ReflectionTestUtils.setField(guard, "logLineRatioThreshold", 0.22d);
        ReflectionTestUtils.setField(guard, "minLogLineMatches", 3);
        ReflectionTestUtils.setField(guard, "allowLegacyNoDocType", true);
        return guard;
    }
}
