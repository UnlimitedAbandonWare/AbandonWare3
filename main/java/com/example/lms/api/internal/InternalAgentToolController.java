package com.example.lms.api.internal;

import com.abandonware.ai.agent.tool.AgentToolInvoker;
import com.example.lms.security.AdminTokenGuardInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.abandonware.ai.agent.tool.request.ToolContext;

import java.util.Map;

@RestController
@RequestMapping("/internal/agent")
public class InternalAgentToolController {
    private final AgentToolInvoker invoker;
    private final AdminTokenGuardInterceptor guard;

    @Value("${agent.tools.api.enabled:false}")
    private boolean apiEnabled;

    public InternalAgentToolController(AgentToolInvoker invoker, AdminTokenGuardInterceptor guard) {
        this.invoker = invoker;
        this.guard = guard;
    }

    @GetMapping("/tools")
    public Map<String, Object> tools(HttpServletRequest request) {
        ensureEnabledAndAuthorized(request);
        return invoker.describeTools();
    }

    @PostMapping("/tools/{toolId}:invoke")
    public Map<String, Object> invokeTool(@PathVariable String toolId,
                                          @RequestBody(required = false) Map<String, Object> input,
                                          HttpServletRequest request) {
        ensureEnabledAndAuthorized(request);
        String sessionId = normalizeSessionId(request == null ? null : request.getHeader("X-Session-Id"));
        ToolContext context = new ToolContext(sessionId, null);
        return invoker.invoke(toolId, input == null ? Map.of() : input, context, true);
    }

    private void ensureEnabledAndAuthorized(HttpServletRequest request) {
        if (!apiEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent_tools_api_disabled");
        }
        if (!guard.hasConfiguredToken() || !guard.isPresentedTokenAuthorized(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_token_required");
        }
    }

    private static String normalizeSessionId(String sessionId) {
        if (sessionId == null) {
            return "internal-agent";
        }
        String trimmed = sessionId.trim();
        return trimmed.isEmpty() ? "internal-agent" : trimmed;
    }
}
