package com.example.lms.service.rag.orchestrator;

import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedRagOrchestratorProviderTraceIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> {
                TraceStore.put("webSearch.returnedCount", 3);
                TraceStore.put("webSearch.afterFilterCount", 0);
                TraceStore.put("rag.returnedCount", 3);
                TraceStore.put("rag.afterFilterCount", 0);
                return List.of();
            })
            .withBean(UnifiedRagOrchestrator.class);

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void realQueryPathConvertsProviderTraceStarvationIntoRagEvalSignalsWithoutRawQuery() {
        TraceStore.clear();

        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
            request.query = "raw starvation query should not leak";
            request.useWeb = true;
            request.useVector = false;
            request.useKg = false;
            request.useBm25 = false;
            request.enableBiEncoder = false;
            request.enableDiversity = false;
            request.enableOnnx = false;
            request.topK = 5;

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

            @SuppressWarnings("unchecked")
            List<String> starvationSignals =
                    (List<String>) response.debug.get("rag.eval.afterFilterStarvationSignals");
            assertTrue(starvationSignals.stream().anyMatch(signal -> signal.startsWith("webSearch:")));
            assertTrue(starvationSignals.stream().anyMatch(signal -> signal.startsWith("rag:")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> thresholdBreaks =
                    (List<Map<String, Object>>) response.debug.get("rag.eval.thresholdBreaks");
            assertTrue(thresholdBreaks.stream()
                    .anyMatch(row -> "after_filter_starvation".equals(row.get("label"))));

            @SuppressWarnings("unchecked")
            Map<String, Object> bottleneck =
                    (Map<String, Object>) response.debug.get("rag.eval.bottleneck");
            assertTrue(bottleneck.containsKey("label"));

            @SuppressWarnings("unchecked")
            Map<String, Object> fingerprint =
                    (Map<String, Object>) response.debug.get("rag.eval.queryFingerprint");
            assertTrue(fingerprint.containsKey("queryHash"));
            assertTrue(fingerprint.containsKey("length"));
            assertTrue(fingerprint.containsKey("tokenBucket"));

            assertFalse(String.valueOf(response.debug).contains("raw starvation query should not leak"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("raw starvation query should not leak"));
        });
    }
}
