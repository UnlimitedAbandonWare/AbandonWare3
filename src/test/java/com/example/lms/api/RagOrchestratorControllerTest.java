package com.example.lms.api;

import com.example.lms.service.rag.langgraph.RagGraphExecutor;
import com.example.lms.service.rag.langgraph.RagGraphProperties;
import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RagOrchestratorControllerTest {

    @Test
    void queryEndpointDelegatesRequestBodyThroughFacade() throws Exception {
        FakeOrchestrator legacy = new FakeOrchestrator();
        MockMvc mvc = standaloneSetup(controller(legacy)).build();

        mvc.perform(post("/api/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"langgraph endpoint\",\"planId\":\"probe.search.v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("legacy"))
                .andExpect(jsonPath("$.planApplied").value("probe.search.v1"))
                .andExpect(jsonPath("$['debug']['langgraph.mode']").value("off"))
                .andExpect(jsonPath("$['debug']['rag.eval.queryFingerprint']['queryHash']").value("hash-only"))
                .andExpect(jsonPath("$['debug']['rag.eval.thresholdBreaks'][0]['label']")
                        .value("after_filter_starvation"));

        assertEquals(1, legacy.calls);
        assertEquals("langgraph endpoint", legacy.lastRequest.query);
        assertEquals("probe.search.v1", legacy.lastRequest.planId);
    }

    @Test
    void probeEndpointBuildsSaferProbeRequest() throws Exception {
        FakeOrchestrator legacy = new FakeOrchestrator();
        MockMvc mvc = standaloneSetup(controller(legacy)).build();

        mvc.perform(post("/api/rag/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"probe langgraph\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("legacy"))
                .andExpect(jsonPath("$.planApplied").value("safe_autorun.v1"))
                .andExpect(jsonPath("$['debug']['langgraph.mode']").value("off"))
                .andExpect(jsonPath("$['debug']['rag.eval.queryFingerprint']['queryHash']").value("hash-only"));

        assertEquals(1, legacy.calls);
        assertEquals("probe langgraph", legacy.lastRequest.query);
        assertTrue(legacy.lastRequest.enableSelfAsk);
        assertFalse(legacy.lastRequest.whitelistOnly);
    }

    private static RagOrchestratorController controller(FakeOrchestrator legacy) {
        RagGraphProperties properties = new RagGraphProperties();
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, (RagGraphExecutor) null, properties);
        return new RagOrchestratorController(facade);
    }

    private static final class FakeOrchestrator extends UnifiedRagOrchestrator {
        private int calls;
        private QueryRequest lastRequest;

        @Override
        public QueryResponse query(QueryRequest req) {
            calls++;
            lastRequest = req;
            QueryResponse response = new QueryResponse();
            response.requestId = "legacy";
            response.planApplied = req == null ? null : req.planId;
            response.debug.put("rag.eval.queryFingerprint", java.util.Map.of(
                    "queryHash", "hash-only",
                    "length", req == null || req.query == null ? 0 : req.query.length(),
                    "tokenBucket", "1-4"));
            response.debug.put("rag.eval.thresholdBreaks", java.util.List.of(java.util.Map.of(
                    "label", "after_filter_starvation",
                    "comparator", "<=",
                    "severity", 1.0d,
                    "stage", "filter")));
            return response;
        }
    }
}
