package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.WebSearchGateway;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;




/**
 * Performs a web search for recent information.  Requires the {@code web.get}
 * scope.  This shim returns an empty list.
 */
@Component
@ConditionalOnBean(WebSearchGateway.class)
@RequiresScopes({ToolScope.WEB_GET})
public class WebSearchTool implements AgentTool {
    private final WebSearchGateway search;

    public WebSearchTool(WebSearchGateway search) { this.search = search; }

    @Override
    public String id() {
        return "web.search";
    }

    @Override
    public String description() {
        return "Perform a web search for up to topK recent results.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request.input();
        String query = textOrNull(input.get("query"));
        Integer topK = positiveIntOrNull(input.get("topK"), "topK");
        String lang = textOrNull(input.get("lang"));
        int requestedCount = topK != null ? topK : 5;
        String requestedLang = lang != null ? lang : "ko";
        TraceStore.put("web.search.tool.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("web.search.tool.queryLength", query == null ? 0 : query.length());
        TraceStore.put("web.search.tool.providerRequested",
                SafeRedactor.traceLabelOrFallback(search.getClass().getSimpleName(), "unknown"));
        TraceStore.put("web.search.tool.requestedCount", requestedCount);
        List<Map<String, Object>> results;
        try {
            results = search.searchAndRank(query, requestedCount, requestedLang);
            if (results == null) {
                results = List.of();
            }
            TraceStore.put("web.search.tool.status", "OK");
            TraceStore.put("web.search.tool.returnedCount", results.size());
            TraceStore.put("web.search.tool.zeroResults", results.isEmpty());
            TraceStore.put("web.search.tool.skipped.reason", null);
        } catch (RuntimeException ex) {
            TraceStore.put("web.search.tool.status", "FAIL_SOFT");
            TraceStore.put("web.search.tool.skipped.reason", "TIMEOUT_OR_EXCEPTION");
            TraceStore.put("web.search.tool.failReason",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            TraceStore.put("web.search.tool.failMsgHash", SafeRedactor.hashValue(ex.getMessage()));
            TraceStore.put("web.search.tool.returnedCount", 0);
            TraceStore.put("web.search.tool.zeroResults", true);
            results = List.of();
        }
        return ToolResponse.ok().put("results", results);
    }

    private static String textOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer positiveIntOrNull(Object value, String stage) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException error) {
            traceSuppressed(stage, value, error);
            return null;
        }
    }

    private static void traceSuppressed(String stage, Object value, Throwable error) {
        String raw = value == null ? null : String.valueOf(value);
        TraceStore.put("web.search.tool.suppressed", true);
        TraceStore.put("web.search.tool.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("web.search.tool.suppressed.errorType",
                error instanceof NumberFormatException ? "invalid_number" : error.getClass().getSimpleName());
        TraceStore.put("web.search.tool.suppressed.valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put("web.search.tool.suppressed.valueLength", raw == null ? 0 : raw.length());
    }
}
