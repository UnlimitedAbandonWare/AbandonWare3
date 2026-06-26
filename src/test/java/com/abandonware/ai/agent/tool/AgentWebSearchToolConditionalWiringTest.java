package com.abandonware.ai.agent.tool;

import com.abandonware.ai.agent.integrations.AcmeAICoreGateway;
import com.abandonware.ai.agent.integrations.WebSearchGateway;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.abandonware.ai.agent.tool.impl.WebSearchTool;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSearchToolConditionalWiringTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void acmeGatewayBacksOffWhenRankingPortIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(AcmeGatewayOnlyConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AcmeAICoreGateway.class);
                });
    }

    @Test
    void webSearchToolRecordsHashCountsAndStatus() {
        WebSearchGateway gateway = mock(WebSearchGateway.class);
        List<Map<String, Object>> results = List.of(Map.of("title", "doc"));
        when(gateway.searchAndRank("private query sk-" + "redactioncontract1234567890", 4, "en"))
                .thenReturn(results);
        WebSearchTool tool = new WebSearchTool(gateway);

        ToolResponse response = tool.execute(new ToolRequest(
                Map.of("query", "private query sk-" + "redactioncontract1234567890", "topK", 4, "lang", "en"),
                new ToolContext("ctx", null)));

        assertEquals(results, response.data().get("results"));
        assertEquals("OK", TraceStore.get("web.search.tool.status"));
        assertEquals(4, TraceStore.get("web.search.tool.requestedCount"));
        assertEquals(1, TraceStore.get("web.search.tool.returnedCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.search.tool.zeroResults"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private query"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("sk-redactioncontract"));
    }

    @Test
    void webSearchToolAcceptsStringTopKAndNormalizesLang() {
        WebSearchGateway gateway = mock(WebSearchGateway.class);
        List<Map<String, Object>> results = List.of(Map.of("title", "doc"));
        when(gateway.searchAndRank("q", 3, "en")).thenReturn(results);
        WebSearchTool tool = new WebSearchTool(gateway);

        ToolResponse response = tool.execute(new ToolRequest(
                Map.of("query", "q", "topK", "3", "lang", " en "),
                new ToolContext("ctx", null)));

        assertEquals(results, response.data().get("results"));
        assertEquals(3, TraceStore.get("web.search.tool.requestedCount"));
        verify(gateway).searchAndRank("q", 3, "en");
    }

    @Test
    void webSearchToolInvalidTopKLeavesRedactedBreadcrumb() {
        WebSearchGateway gateway = mock(WebSearchGateway.class);
        when(gateway.searchAndRank("q", 5, "ko")).thenReturn(List.of());
        WebSearchTool tool = new WebSearchTool(gateway);

        tool.execute(new ToolRequest(
                Map.of("query", "q", "topK", "private topk"),
                new ToolContext("ctx", null)));

        assertEquals(5, TraceStore.get("web.search.tool.requestedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.search.tool.suppressed"));
        assertEquals("topK", TraceStore.get("web.search.tool.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("web.search.tool.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private topk"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchToolFailsSoftAndLeavesReasonOnException() {
        WebSearchGateway gateway = mock(WebSearchGateway.class);
        when(gateway.searchAndRank("q", 5, "ko")).thenThrow(new RuntimeException("Authorization=Bearer secret"));
        WebSearchTool tool = new WebSearchTool(gateway);

        ToolResponse response = tool.execute(new ToolRequest(Map.of("query", "q"), new ToolContext("ctx", null)));

        assertEquals(List.of(), response.data().get("results"));
        assertEquals("FAIL_SOFT", TraceStore.get("web.search.tool.status"));
        assertEquals("TIMEOUT_OR_EXCEPTION", TraceStore.get("web.search.tool.skipped.reason"));
        assertEquals(0, TraceStore.get("web.search.tool.returnedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.search.tool.zeroResults"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("Authorization"));
    }

    @Test
    void webSearchToolBacksOffWhenGatewayIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(WebSearchToolOnlyConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(WebSearchTool.class);
                });
    }

    @Configuration
    @ComponentScan(
            basePackageClasses = AcmeAICoreGateway.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = AcmeAICoreGateway.class))
    static class AcmeGatewayOnlyConfig {
    }

    @Configuration
    @ComponentScan(
            basePackageClasses = WebSearchTool.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = WebSearchTool.class))
    static class WebSearchToolOnlyConfig {
    }
}
