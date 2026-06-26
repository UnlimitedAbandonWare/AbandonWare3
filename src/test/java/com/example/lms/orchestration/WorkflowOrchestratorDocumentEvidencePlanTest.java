package com.example.lms.orchestration;

import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowOrchestratorDocumentEvidencePlanTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void uploadedDocumentQuestionSelectsDocumentEvidencePlanByDefault() {
        WorkflowOrchestrator orchestrator = orchestrator();
        GuardContext ctx = new GuardContext();

        String selected = orchestrator.ensurePlanSelected(
                ctx,
                AnswerMode.BALANCED,
                QueryDomain.GENERAL,
                "summarize the uploaded document",
                true);

        assertEquals("document_evidence.v1", selected);
        assertEquals("document_evidence.v1", ctx.getPlanId());
        assertEquals(Boolean.TRUE, TraceStore.get("plan.documentEvidence"));
    }

    @Test
    void explicitHeaderModeStillWinsOverDocumentAutoSelection() {
        WorkflowOrchestrator orchestrator = orchestrator();
        GuardContext ctx = new GuardContext();
        ctx.setHeaderMode("brave");

        String selected = orchestrator.ensurePlanSelected(
                ctx,
                AnswerMode.BALANCED,
                QueryDomain.GENERAL,
                "summarize the uploaded document",
                true);

        assertEquals("brave.v1", selected);
        assertEquals("brave.v1", ctx.getPlanId());
    }

    @Test
    void freeHeaderModeSelectsCostSaverInsteadOfBraveCreativePlan() {
        WorkflowOrchestrator orchestrator = orchestrator();
        GuardContext ctx = new GuardContext();
        ctx.setHeaderMode("free");

        String selected = orchestrator.ensurePlanSelected(
                ctx,
                AnswerMode.BALANCED,
                QueryDomain.GENERAL,
                "short answer please",
                false);

        assertEquals("ap9_cost_saver.v1", selected);
        assertEquals("ap9_cost_saver.v1", ctx.getPlanId());
    }

    private static WorkflowOrchestrator orchestrator() {
        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                new PlanHintApplier(new DefaultResourceLoader()));
        ReflectionTestUtils.setField(orchestrator, "enabled", true);
        ReflectionTestUtils.setField(orchestrator, "defaultPlanId", "safe_autorun.v1");
        ReflectionTestUtils.setField(orchestrator, "safePlanId", "safe_autorun.v1");
        ReflectionTestUtils.setField(orchestrator, "creativePlanId", "brave.v1");
        ReflectionTestUtils.setField(orchestrator, "recencyPlanId", "recency_first.v1");
        ReflectionTestUtils.setField(orchestrator, "entityPlanId", "kg_first.v1");
        ReflectionTestUtils.setField(orchestrator, "documentPlanId", "document_evidence.v1");
        return orchestrator;
    }
}
