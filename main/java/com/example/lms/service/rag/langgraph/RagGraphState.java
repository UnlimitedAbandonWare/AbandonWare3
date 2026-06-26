package com.example.lms.service.rag.langgraph;

import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryTrace;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.LinkedHashMap;
import java.util.Map;

public class RagGraphState extends AgentState {

    public static final String QUERY = "query";
    public static final String PLAN_ID = "planId";
    public static final String REQUEST = "request";
    public static final String TRACE = "trace";
    public static final String RESPONSE = "response";
    public static final String DEBUG = "debug";
    public static final String FAILURE_REASON = "failureReason";
    public static final String CONTROL_DECISION = "controlDecision";
    public static final String SAFETY_MODE = "safetyMode";
    public static final String RETRIEVAL_POSTURE = "retrievalPosture";
    public static final String FAILURE_ACTION = "failureAction";
    public static final String TRANSITION_TRACE = "transitionTrace";

    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry(QUERY, baseChannel()),
            Map.entry(PLAN_ID, baseChannel()),
            Map.entry(REQUEST, baseChannel()),
            Map.entry(TRACE, baseChannel()),
            Map.entry(RESPONSE, baseChannel()),
            Map.entry(DEBUG, baseChannel()),
            Map.entry(FAILURE_REASON, baseChannel()),
            Map.entry(CONTROL_DECISION, baseChannel()),
            Map.entry(SAFETY_MODE, baseChannel()),
            Map.entry(RETRIEVAL_POSTURE, baseChannel()),
            Map.entry(FAILURE_ACTION, baseChannel()),
            Map.entry(TRANSITION_TRACE, baseChannel())
    );

    public RagGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public String query() {
        return value(QUERY).map(String::valueOf).orElse("");
    }

    public String planId() {
        return value(PLAN_ID).map(String::valueOf).orElse(null);
    }

    public QueryRequest request() {
        return value(REQUEST).map(QueryRequest.class::cast).orElse(null);
    }

    public QueryTrace trace() {
        return value(TRACE).map(QueryTrace.class::cast).orElse(null);
    }

    public QueryResponse response() {
        return value(RESPONSE).map(QueryResponse.class::cast).orElse(null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> debug() {
        return value(DEBUG)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(m -> new LinkedHashMap<String, Object>((Map<String, Object>) m))
                .orElseGet(LinkedHashMap::new);
    }

    public String failureReason() {
        return value(FAILURE_REASON).map(String::valueOf).orElse("");
    }

    public RagGraphControlPolicy.Decision controlDecision() {
        return value(CONTROL_DECISION)
                .filter(RagGraphControlPolicy.Decision.class::isInstance)
                .map(RagGraphControlPolicy.Decision.class::cast)
                .orElse(null);
    }

    public String safetyMode() {
        return value(SAFETY_MODE).map(String::valueOf).orElse("");
    }

    public String retrievalPosture() {
        return value(RETRIEVAL_POSTURE).map(String::valueOf).orElse("");
    }

    public String failureAction() {
        return value(FAILURE_ACTION).map(String::valueOf).orElse("");
    }

    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> transitionTrace() {
        return value(TRANSITION_TRACE)
                .filter(java.util.List.class::isInstance)
                .map(java.util.List.class::cast)
                .map(rows -> new java.util.ArrayList<Map<String, Object>>(
                        (java.util.List<Map<String, Object>>) rows))
                .orElseGet(java.util.ArrayList::new);
    }

    private static Channel<?> baseChannel() {
        return Channels.<Object>base(() -> null);
    }
}
