package com.example.lms.prompt.pose;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.SelfAskPlanner;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SelfAskPlannerPromptPoseTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void traceLaneWeightsApplyWhenCallerWeightsAreEmpty() {
        PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                List.of(), List.of(), 1, 3, 3,
                Map.of("BQ", 0.25d, "ER", 1.0d, "RC", 2.5d),
                0.0d, 0.4d, 0, 0.8d, "ok");
        PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "hashhashhash", "ko", "general", 10));

        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);
        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes("질문", 300L, Double.NaN, Map.of());

        assertEquals(3, lanes.size());
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.promptPose.applied"));
        assertEquals("LOCAL_LIGHT", TraceStore.get("selfask.promptPose.arm"));
        assertTrue(lanes.stream().anyMatch(sq -> sq.type == SelfAskPlanner.SubQuestionType.RC
                && Double.valueOf(2.5d).equals(sq.meta.get("weight"))));
    }

    @Test
    void promptPoseArmTraceUsesSafeLabelWhenTraceStoreIsPolluted() {
        String rawArm = "LOCAL_LIGHT token=test-secret-abcdefghijklmnop";
        TraceStore.put(PromptPoseTrace.ARM, rawArm);

        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);
        planner.generateThreeLanes("query", 1L);

        String stored = String.valueOf(TraceStore.get("selfask.promptPose.arm"));
        assertTrue(stored.startsWith("hash:"), stored);
        assertFalse(stored.contains("test-secret-abcdefghijklmnop"), stored);
        assertFalse(stored.contains(rawArm), stored);
    }

    @Test
    void selfAskCountLimitsSelectedLanesByPromptPoseWeightOrder() {
        PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                List.of(), List.of(), 0, 0, 1,
                Map.of("BQ", 0.25d, "ER", 1.0d, "RC", 2.5d),
                0.0d, 0.0d, 0, 0.8d, "ok");
        PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "hashhashhash", "ko", "general", 10));

        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);
        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes("질문", 100L, Double.NaN, Map.of());

        assertEquals(1, lanes.size());
        assertEquals(SelfAskPlanner.SubQuestionType.RC, lanes.get(0).type);
        assertEquals(1, TraceStore.get("selfask.3way.laneLimit"));
        assertEquals(List.of("RC"), TraceStore.get("selfask.3way.laneOrder"));
    }

    @Test
    void explicitCallerLaneWeightsIgnorePromptPoseSelfAskCount() {
        PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                List.of(), List.of(), 0, 0, 1,
                Map.of("RC", 2.5d), 0.0d, 0.0d, 0, 0.8d, "ok");
        PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "hashhashhash", "ko", "general", 10));

        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);
        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes("질문", 300L, Double.NaN,
                Map.of("BQ", 2.5d, "ER", 1.0d, "RC", 0.25d));

        assertEquals(3, lanes.size());
        assertEquals(3, TraceStore.get("selfask.3way.laneLimit"));
        assertEquals(List.of("BQ", "ER", "RC"), TraceStore.get("selfask.3way.laneOrder"));
    }
}
