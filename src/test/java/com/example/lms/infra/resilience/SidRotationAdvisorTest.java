package com.example.lms.infra.resilience;

import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidRotationAdvisorTest {

    @Test
    void snapshotLastReasonDoesNotExposeRawSecrets() {
        SidRotationAdvisor advisor = new SidRotationAdvisor();
        ReflectionTestUtils.setField(advisor, "enabled", true);
        ReflectionTestUtils.setField(advisor, "globalOnly", true);
        ReflectionTestUtils.setField(advisor, "windowMs", 600_000L);
        ReflectionTestUtils.setField(advisor, "poisonThreshold", 1);
        String fakeKey = "sk-" + "sid-rotation-placeholder-1234567890";
        String reason = "poison_guard api_key=" + fakeKey;

        advisor.recordPoison(
                LangChainRAGService.GLOBAL_SID,
                reason);

        Map<String, Object> snapshot = advisor.snapshotFor(LangChainRAGService.GLOBAL_SID);
        String lastReason = String.valueOf(snapshot.get("lastReason"));

        assertTrue(snapshot.containsKey("lastReason"));
        assertEquals(SafeRedactor.traceLabelOrFallback(reason, "unknown"), lastReason);
        assertFalse(lastReason.contains(fakeKey));
        assertFalse(lastReason.contains("api_key=" + fakeKey));
    }
}
