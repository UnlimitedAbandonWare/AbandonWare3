package com.abandonware.ai.agent.tool;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.consent.ConsentContext;
import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.consent.ConsentToken;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.abandonware.ai.agent.contract.ToolManifestEntry;
import com.abandonware.ai.agent.contract.ToolManifestSnapshot;
import com.abandonware.ai.agent.policy.ToolPolicyEnforcer;
import com.abandonware.ai.agent.trace.AgentBreadcrumbMemory;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentToolInvoker {
    private final ToolRegistry registry;
    private final ToolManifestCatalog catalog;
    private final ToolPolicyEnforcer policy;
    private final ObjectProvider<ConsentService> consentService;
    private final ObjectProvider<DebugEventStore> debugEvents;
    private final AgentToolArtifactWriter artifactWriter;
    private final ObjectProvider<FailurePatternMemoryService> failurePatternMemory;

    @Value("${agent.tools.response.max-inline-bytes:65536}")
    private int configuredMaxInlineBytes;

    public AgentToolInvoker(ToolRegistry registry,
                            ToolManifestCatalog catalog,
                            ToolPolicyEnforcer policy,
                            ObjectProvider<ConsentService> consentService,
                            ObjectProvider<DebugEventStore> debugEvents,
                            AgentToolArtifactWriter artifactWriter) {
        this(registry, catalog, policy, consentService, debugEvents, artifactWriter, null);
    }

    public AgentToolInvoker(ToolRegistry registry,
                            ToolManifestCatalog catalog,
                            ToolPolicyEnforcer policy,
                            ObjectProvider<ConsentService> consentService,
                            ObjectProvider<DebugEventStore> debugEvents,
                            AgentToolArtifactWriter artifactWriter,
                            ObjectProvider<FailurePatternMemoryService> failurePatternMemory) {
        this.registry = registry;
        this.catalog = catalog;
        this.policy = policy;
        this.consentService = consentService;
        this.debugEvents = debugEvents;
        this.artifactWriter = artifactWriter;
        this.failurePatternMemory = failurePatternMemory;
    }

    public Map<String, Object> invoke(String toolId,
                                      Map<String, Object> input,
                                      ToolContext context,
                                      boolean adminAuthorized) {
        long started = System.nanoTime();
        long startedMillis = System.currentTimeMillis();
        String id = toolId == null ? "" : toolId.trim();
        if (id.isBlank()) {
            throw ToolInvocationException.badRequest("missing_tool_id");
        }

        ToolManifestSnapshot snapshot = catalog.load();
        ToolManifestEntry entry = snapshot.entry(id);
        if (entry == null) {
            traceInvocation(id, adminAuthorized, false, "POLICY_REJECTED", startedMillis,
                    elapsedMs(started), "manifest_missing", null);
            AgentBreadcrumbMemory.toolInvocation(id, adminAuthorized, false, "POLICY_REJECTED", startedMillis,
                    elapsedMs(started), "manifest_missing", null, input, null, failurePatternMemory());
            emit(id, "tool.invoke.denied", "manifest_missing", adminAuthorized, 0, null);
            throw ToolInvocationException.notFound("tool_manifest_missing");
        }

        AgentTool tool = registry.get(id).orElse(null);
        if (tool == null) {
            traceInvocation(id, adminAuthorized, false, "POLICY_REJECTED", startedMillis,
                    elapsedMs(started), "registry_missing", null);
            AgentBreadcrumbMemory.toolInvocation(id, adminAuthorized, false, "POLICY_REJECTED", startedMillis,
                    elapsedMs(started), "registry_missing", null, input, null, failurePatternMemory());
            emit(id, "tool.invoke.denied", "registry_missing", adminAuthorized, 0, null);
            throw ToolInvocationException.notFound("tool_registry_missing");
        }

        boolean scopesSatisfied = false;
        try {
            scopesSatisfied = ensureScopes(tool, context, adminAuthorized);
            policy.beforeCall(id, entry, adminAuthorized, scopesSatisfied);
        } catch (ToolInvocationException ex) {
            String status = "missing_consent_service".equals(ex.code()) ? "CONSENT_DENIED" : "POLICY_REJECTED";
            traceInvocation(id, adminAuthorized, scopesSatisfied, status, startedMillis,
                    elapsedMs(started), ex.code(), ex);
            AgentBreadcrumbMemory.toolInvocation(id, adminAuthorized, scopesSatisfied, status, startedMillis,
                    elapsedMs(started), ex.code(), ex, input, null, failurePatternMemory());
            emit(id, "tool.invoke.denied", ex.code(), adminAuthorized, elapsedMs(started), null);
            throw ex;
        }

        Map<String, Object> safeInput = input == null ? Map.of() : input;
        try {
            ToolContext safeContext = context == null ? new ToolContext("internal-agent", new ConsentToken("internal-agent")) : context;
            ToolResponse response = tool.execute(new ToolRequest(safeInput, safeContext));
            Map<String, Object> sanitizedData = sanitizeMap(response == null ? Map.of() : response.data(), entry.maxOutputBytes());
            int maxInline = Math.max(1024, Math.min(entry.maxOutputBytes(), configuredMaxInlineBytes <= 0 ? 65536 : configuredMaxInlineBytes));
            byte[] bytes = artifactWriter.toJsonBytes(sanitizedData);

            Map<String, Object> result = baseResult(id, true, started);
            result.put("readOnly", entry.readOnly());
            result.put("risk", entry.risk());
            if (entry.returnsLargePayloadByReference() || bytes.length > maxInline) {
                result.put("data", Map.of("summary", "payload stored as artifact", "inlineBytes", bytes.length));
                result.put("artifact", artifactWriter.write("tool-response", id, sanitizedData));
                result.put("truncated", true);
            } else {
                result.put("data", sanitizedData);
                result.put("truncated", false);
            }
            policy.afterCall(id);
            traceInvocation(id, adminAuthorized, scopesSatisfied, "OK", startedMillis,
                    elapsedMs(started), null, null);
            AgentBreadcrumbMemory.toolInvocation(id, adminAuthorized, scopesSatisfied, "OK", startedMillis,
                    elapsedMs(started), null, null, safeInput, sanitizedData, failurePatternMemory());
            emit(id, "tool.invoke.ok", "ok", adminAuthorized, elapsedMs(started), null);
            return result;
        } catch (ToolInvocationException ex) {
            traceInvocation(id, adminAuthorized, scopesSatisfied, "FAIL", startedMillis,
                    elapsedMs(started), ex.code(), ex);
            AgentBreadcrumbMemory.toolInvocation(id, adminAuthorized, scopesSatisfied, "FAIL", startedMillis,
                    elapsedMs(started), ex.code(), ex, safeInput, null, failurePatternMemory());
            emit(id, "tool.invoke.denied", ex.code(), adminAuthorized, elapsedMs(started), null);
            throw ex;
        } catch (Exception ex) {
            traceInvocation(id, adminAuthorized, scopesSatisfied, "FAIL", startedMillis,
                    elapsedMs(started), "tool_execution_failed", ex);
            AgentBreadcrumbMemory.toolInvocation(id, adminAuthorized, scopesSatisfied, "FAIL", startedMillis,
                    elapsedMs(started), "tool_execution_failed", ex, safeInput, null, failurePatternMemory());
            emit(id, "tool.invoke.error", "tool_execution_failed", adminAuthorized, elapsedMs(started), ex);
            throw ToolInvocationException.failed("tool_execution_failed");
        }
    }

    public Map<String, Object> describeTools() {
        ToolManifestSnapshot snapshot = catalog.load();
        List<Map<String, Object>> tools = snapshot.entries().values().stream()
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", entry.id());
                    row.put("enabled", entry.enabled());
                    row.put("registered", registry.get(entry.id()).isPresent());
                    row.put("description", SafeRedactor.safeMessage(entry.description(), 240));
                    row.put("risk", entry.risk());
                    row.put("readOnly", entry.readOnly());
                    row.put("ownerTokenRequired", entry.ownerTokenRequired());
                    row.put("scopes", entry.scopes());
                    row.put("disabledReason", SafeRedactor.traceLabelOrFallback(entry.disabledReason(), ""));
                    return row;
                })
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("resourcePathHash", SafeRedactor.hashValue(snapshot.resourcePath()));
        out.put("resourcePathLength", snapshot.resourcePath() == null ? 0 : snapshot.resourcePath().length());
        out.put("tools", tools);
        out.put("duplicateToolIds", registry.duplicateToolIdCounts());
        out.put("issueCount", snapshot.issues().size());
        out.put("warningCount", snapshot.warnings().size());
        return out;
    }

    private boolean ensureScopes(AgentTool tool, ToolContext context, boolean adminAuthorized) {
        RequiresScopes annotation = tool.getClass().getAnnotation(RequiresScopes.class);
        ToolScope[] required = annotation == null ? new ToolScope[0] : annotation.value();
        if (required.length == 0 || adminAuthorized) {
            return true;
        }
        ConsentService service = consentService == null ? null : consentService.getIfAvailable();
        if (service == null) {
            throw ToolInvocationException.forbidden("missing_consent_service");
        }
        ToolContext safeContext = context == null ? new ToolContext("internal-agent", null) : context;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("toolId", tool.id());
        attrs.put("sessionIdHash", SafeRedactor.hashValue(safeContext.sessionId()));
        attrs.put("debugTrace", safeContext.debugTrace());
        attrs.put("scopes", Arrays.stream(required).map(ToolScope::value).toList());
        service.ensureGranted(safeContext.consent(), required, new ConsentContext(attrs));
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeMap(Map<String, Object> data, int maxOutputBytes) {
        Object sanitized = SafeRedactor.diagnosticValue("toolResponse", data == null ? Map.of() : data,
                Math.max(256, Math.min(maxOutputBytes, 4000)));
        if (sanitized instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
            return out;
        }
        return Map.of("value", sanitized);
    }

    private Map<String, Object> baseResult(String toolId, boolean ok, long started) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok);
        result.put("toolId", toolId);
        result.put("elapsedMs", elapsedMs(started));
        return result;
    }

    private void emit(String toolId,
                      String fingerprint,
                      String reason,
                      boolean adminAuthorized,
                      long elapsedMs,
                      Throwable error) {
        DebugEventStore store = debugEvents == null ? null : debugEvents.getIfAvailable();
        if (store == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolId", toolId);
        data.put("reason", reason);
        data.put("adminAuthorized", adminAuthorized);
        data.put("elapsedMs", elapsedMs);
        store.emit(DebugProbeType.ORCHESTRATION,
                error == null ? DebugEventLevel.INFO : DebugEventLevel.WARN,
                fingerprint + ":" + toolId,
                fingerprint,
                "AgentToolInvoker",
                data,
                error);
    }

    private static void traceInvocation(String toolId,
                                        boolean adminAuthorized,
                                        boolean scopesSatisfied,
                                        String status,
                                        long startedMillis,
                                        long elapsedMs,
                                        String failReason,
                                        Throwable error) {
        TraceStore.put("tool.invoke.id", SafeRedactor.traceLabelOrFallback(toolId, "unknown"));
        TraceStore.put("tool.invoke.hasConsent", adminAuthorized || scopesSatisfied);
        TraceStore.put("tool.invoke.start", Math.max(0L, startedMillis));
        TraceStore.put("tool.invoke.status", SafeRedactor.traceLabelOrFallback(status, "unknown"));
        TraceStore.put("tool.invoke.durationMs", Math.max(0L, elapsedMs));
        TraceStore.put("tool.invoke.scopesPassed", scopesSatisfied);
        if (failReason == null || failReason.isBlank()) {
            TraceStore.put("tool.invoke.failReason", null);
            TraceStore.put("tool.invoke.failMsgHash", null);
            return;
        }
        TraceStore.put("tool.invoke.failReason", SafeRedactor.traceLabelOrFallback(failReason, "unknown"));
        String message = error == null ? failReason : error.getMessage();
        TraceStore.put("tool.invoke.failMsgHash", SafeRedactor.hashValue(message));
    }

    private FailurePatternMemoryService failurePatternMemory() {
        if (failurePatternMemory == null) {
            return null;
        }
        try {
            return failurePatternMemory.getIfAvailable();
        } catch (RuntimeException ex) {
            TraceStore.put("agent.breadcrumb.memory.stored", false);
            TraceStore.put("agent.breadcrumb.memory.skipReason", "memory_provider_failed");
            TraceStore.put("agent.breadcrumb.memory.errorType",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            return null;
        }
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
