package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class SourceMapTool implements AgentTool {
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final ToolRegistry registry;

    public SourceMapTool(ObjectProvider<RequestMappingHandlerMapping> mappings, ToolRegistry registry) {
        this.mappings = mappings;
        this.registry = registry;
    }

    @Override
    public String id() {
        return "source.map";
    }

    @Override
    public String description() {
        return "List registered tool ids and Spring routes in a bounded redacted map.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolIds", registry.all().stream().map(AgentTool::id).sorted().toList());
        out.put("duplicateToolIds", registry.duplicateToolIdCounts());

        RequestMappingHandlerMapping mapping = mappings == null ? null : mappings.getIfAvailable();
        List<Map<String, Object>> routes = new ArrayList<>();
        if (mapping != null) {
            mapping.getHandlerMethods().forEach((info, handler) -> {
                if (routes.size() >= 250) {
                    return;
                }
                routes.add(route(info));
            });
        }
        out.put("routeCount", mapping == null ? 0 : mapping.getHandlerMethods().size());
        out.put("routes", routes);
        out.put("routesTruncated", mapping != null && mapping.getHandlerMethods().size() > routes.size());
        return ToolResponse.ok().put("sourceMap", out);
    }

    private static Map<String, Object> route(RequestMappingInfo info) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("patterns", new TreeSet<>(info.getPatternValues()));
        row.put("methods", info.getMethodsCondition().getMethods().stream().map(Enum::name).sorted().toList());
        row.put("name", info.getName() == null ? "" : info.getName());
        return row;
    }
}
