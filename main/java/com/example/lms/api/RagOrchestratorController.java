package com.example.lms.api;

import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagOrchestratorController {

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    private final RagOrchestratorFacade orchestrator;

    public RagOrchestratorController(RagOrchestratorFacade orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping(value = "/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResponse query(@RequestBody(required = false) QueryRequest req) {
        if (req == null) {
            req = new QueryRequest();
            req.query = "hello world";
        }
        return orchestrator.query(req);
    }

    @PostMapping(value = "/probe", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResponse probe(@RequestBody(required = false) Map<String, Object> body) {
        QueryRequest req = new QueryRequest();
        Object q = body == null ? null : body.get("q");
        req.query = q == null ? "" : String.valueOf(q);
        req.enableSelfAsk = true;
        req.whitelistOnly = false;
        return orchestrator.query(req);
    }
}
