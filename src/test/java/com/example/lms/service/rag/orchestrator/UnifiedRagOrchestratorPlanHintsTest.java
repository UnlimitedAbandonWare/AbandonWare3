package com.example.lms.service.rag.orchestrator;

import com.example.lms.plan.PlanHintApplier;
import com.example.lms.plan.PlanHints;
import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedRagOrchestratorPlanHintsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PlanHintApplier.class, () -> new PlanHintApplier(new DefaultResourceLoader()))
            .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> contents("web", 30))
            .withBean("vectorRetriever", ContentRetriever.class, () -> query -> contents("vector", 30))
            .withBean("knowledgeGraphHandler", ContentRetriever.class, () -> query -> contents("kg", 10))
            .withBean(UnifiedRagOrchestrator.class);

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void bravePlanAppliesWebTopKAndSelfAskWithoutRawQueryDebug() {
        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            UnifiedRagOrchestrator.QueryRequest request = baseRequest("brave.v1");
            request.query = "raw brave query should not leak";

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

            assertEquals("PlanHintApplier", response.debug.get("plan.source"));
            assertEquals("brave.v1", response.debug.get("plan.id"));
            assertEquals(true, response.debug.get("plan.applied"));
            assertEquals("applied:brave.v1", response.debug.get("planHints"));
            assertEquals("not_used", response.debug.get("planDsl.status"));
            assertEquals(false, response.debug.get("planDsl.loaded"));
            assertFalse(String.valueOf(response.debug.get("planDsl")).startsWith("applied:"));
            String unwiredKeys = String.valueOf(response.debug.get("planDsl.unwiredKeys"));
            assertTrue(unwiredKeys.contains("llm"));
            assertTrue(unwiredKeys.contains("guard"));
            assertTrue(unwiredKeys.contains("plan.when"));
            assertTrue(unwiredKeys.contains("plan.pipeline"));
            assertEquals(response.debug.get("planDsl.unwiredKeys"), TraceStore.get("planDsl.unwiredKeys"));
            assertEquals(18, response.debug.get("plan.webTopK"));
            assertEquals("success:18", response.debug.get("stage.web"));
            assertEquals(true, response.debug.get("plan.selfAsk.enabled"));
            assertEquals("missing_selfAskPlanner", response.debug.get("selfAsk"));
            assertFalse(String.valueOf(response.debug).contains("raw brave query should not leak"));
        });
    }

    @Test
    void ap9CostSaverDisablesCrossEncoderThroughPlanHints() {
        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            UnifiedRagOrchestrator.QueryRequest request = baseRequest("ap9_cost_saver.v1");

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

            assertEquals("ap9_cost_saver.v1", response.debug.get("plan.id"));
            assertEquals(false, response.debug.get("plan.onnx.enabled"));
            assertFalse(request.enableOnnx);
        });
    }

    @Test
    void zero100PlanAppliesPerSourceKValues() {
        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            UnifiedRagOrchestrator.QueryRequest request = baseRequest("zero100.v1");
            request.useKg = true;

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

            assertEquals("zero100.v1", response.debug.get("plan.id"));
            assertEquals(8, response.debug.get("plan.webTopK"));
            assertEquals(4, response.debug.get("plan.vectorTopK"));
            assertEquals(2, response.debug.get("plan.kgTopK"));
            assertEquals("success:8", response.debug.get("stage.web"));
            assertEquals("success:4", response.debug.get("stage.vector"));
            assertEquals("success:2", response.debug.get("stage.kg"));
            assertTrue(String.valueOf(TraceStore.getAll()).contains("zero100.v1"));
        });
    }

    @Test
    void traceStorePlanIdRedactsSensitiveRequestLabel() {
        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            String secret = "sk-" + "planIdSecret123456789012345";
            UnifiedRagOrchestrator.QueryRequest request = baseRequest("plan=" + secret);

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

            Object tracePlanId = TraceStore.get("plan.id");
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(String.valueOf(tracePlanId).contains(secret));
            assertFalse(trace.contains(secret));
            assertFalse(String.valueOf(response.planApplied).contains(secret));
            assertFalse(String.valueOf(response.debug).contains(secret));
        });
    }

    @Test
    void planHintApplyFailureUsesStableReasonWithoutExceptionClass() {
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> new PlanHintApplier(new DefaultResourceLoader()) {
                    @Override
                    public PlanHints load(String planId) {
                        throw new IllegalArgumentException("private plan token should not leak");
                    }
                })
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
                    UnifiedRagOrchestrator.QueryRequest request = baseRequest("failing_plan.v1");
                    request.useWeb = false;
                    request.useVector = false;
                    request.useKg = false;

                    UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

                    assertEquals("plan_apply_failed", response.debug.get("plan.disabledReason"));
                    assertEquals("plan_apply_failed", TraceStore.get("plan.apply.error"));
                    assertFalse(String.valueOf(response.debug).contains("IllegalArgumentException"));
                    assertFalse(String.valueOf(TraceStore.getAll()).contains("IllegalArgumentException"));
                    assertFalse(String.valueOf(response.debug).contains("private plan token should not leak"));
                    assertFalse(String.valueOf(TraceStore.getAll()).contains("private plan token should not leak"));
                });
    }

    @Test
    void numericTraceParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertParserCatchNarrowed(source, "private static double toDouble");
        assertParserCatchNarrowed(source, "private static int traceInt");
        assertParserCatchNarrowed(source, "private static int relationThumbnailInt");
    }

    private static UnifiedRagOrchestrator.QueryRequest baseRequest(String planId) {
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "plan hint verification";
        request.planId = planId;
        request.useWeb = true;
        request.useVector = true;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = true;
        request.topK = 8;
        return request;
    }

    private static List<Content> contents(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Content.from(prefix + " evidence " + i))
                .toList();
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse > start, "parser call should be locatable: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be locatable: " + signature);
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
    }
}
