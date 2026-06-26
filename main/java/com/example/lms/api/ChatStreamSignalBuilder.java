package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

final class ChatStreamSignalBuilder {
    private static final long SLOW_MODEL_TOOK_MS = 60_000L;

    private static final System.Logger LOG = System.getLogger(ChatStreamSignalBuilder.class.getName());

    private ChatStreamSignalBuilder() {
    }

    static ChatStreamEvent.TraceSignal buildTraceSignal(
            Map<String, Object> meta,
            String traceId,
            String requestId,
            String sessionId) {
        Map<String, Object> safeMeta = meta == null ? Map.of() : meta;
        Map<String, Integer> stageCounts = toIntegerMap(firstNonNull(
                safeMeta.get("rag.eval.stageCounts"),
                safeMeta.get("stageCounts")));
        int eventCount = collectionSize(safeMeta.get("orch.events.v1"));
        if (eventCount == 0) {
            eventCount = numberAsInt(safeMeta.get("orch.eventCount"), 0);
        }
        String failureClass = firstNonBlank(
                safeString(safeMeta.get("failureClass")),
                safeString(safeMeta.get("lastFailureReason")),
                safeString(safeMeta.get("rag.eval.failureClass")),
                providerCancellationFailureClass(safeMeta));
        String reasonCode = firstNonBlank(
                safeString(safeMeta.get("reasonCode")),
                safeString(safeMeta.get("lastControlAction")),
                safeString(safeMeta.get("rag.eval.reasonCode")));
        return new ChatStreamEvent.TraceSignal(
                hashIdentifier(firstNonBlank(traceId, safeString(safeMeta.get("trace.id")), safeString(safeMeta.get("traceId")))),
                hashIdentifier(firstNonBlank(requestId, safeString(safeMeta.get("requestId")), safeString(safeMeta.get("x-request-id")))),
                hashIdentifier(firstNonBlank(sessionId, safeString(safeMeta.get("sessionId")), safeString(safeMeta.get("sid")))),
                eventCount,
                failureClass,
                reasonCode,
                stageCounts);
    }

    static ChatStreamEvent.PipelineSnapshot buildPipelineSnapshot(
            Map<String, Object> meta,
            String answerMode,
            Long traceTurnId,
            ChatStreamEvent.TraceSignal traceSignal) {
        Map<String, Object> safeMeta = meta == null ? Map.of() : meta;
        Integer webCount = firstNonNull(
                countValue(safeMeta.get("webCount")),
                countValue(safeMeta.get("web.count")),
                countValue(safeMeta.get("finalWebTopKCount")),
                collectionSizeOrNull(safeMeta.get("finalWebTopK")));
        Integer vectorCount = firstNonNull(
                countValue(safeMeta.get("vectorCount")),
                countValue(safeMeta.get("vector.count")),
                countValue(safeMeta.get("finalVectorTopKCount")),
                collectionSizeOrNull(safeMeta.get("finalVectorTopK")));
        Integer finalContextCount = firstNonNull(
                countValue(safeMeta.get("finalContextCount")),
                countValue(safeMeta.get("final.context.count")),
                countValue(safeMeta.get("prompt.context.count")));
        if (finalContextCount == null && (webCount != null || vectorCount != null)) {
            finalContextCount = Math.max(0, webCount == null ? 0 : webCount) + Math.max(0, vectorCount == null ? 0 : vectorCount);
        }

        String failureClass = firstNonBlank(
                traceSignal == null ? null : traceSignal.failureClass(),
                safeString(safeMeta.get("failureClass")),
                safeString(safeMeta.get("lastFailureReason")),
                safeString(safeMeta.get("rag.eval.failureClass")),
                providerCancellationFailureClass(safeMeta));
        String disabledReason = firstNonBlank(
                safeString(safeMeta.get("disabledReason")),
                safeString(safeMeta.get("disabledReasonCanonical")),
                safeString(safeMeta.get("web.naver.disabledReasonCanonical")),
                safeString(safeMeta.get("web.brave.disabledReasonCanonical")),
                safeString(safeMeta.get("web.serpapi.disabledReasonCanonical")),
                safeString(safeMeta.get("web.tavily.disabledReasonCanonical")),
                safeString(safeMeta.get("selfask.3way.api.disabledReason")),
                safeString(safeMeta.get("llmrouter.api.disabledReason")));

        ChatStreamEvent.PipelineSnapshot snapshot = new ChatStreamEvent.PipelineSnapshot(
                firstNonBlank(
                        safeString(safeMeta.get("plan.id")),
                        safeString(safeMeta.get("plan.auto")),
                        safeString(safeMeta.get("planId")),
                        safeString(safeMeta.get("planApplied"))),
                firstNonBlank(
                        safeString(safeMeta.get("route")),
                        safeString(safeMeta.get("rag.route")),
                        safeString(safeMeta.get("rag.route.hint")),
                        safeString(safeMeta.get("answer.route"))),
                firstNonBlank(answerMode, safeString(safeMeta.get("answer.mode"))),
                traceTurnId,
                webCount,
                vectorCount,
                finalContextCount,
                firstNonNull(
                        asDouble(safeMeta.get("citationCoverage"), null),
                        asDouble(safeMeta.get("citation.coverage"), null),
                        asDouble(safeMeta.get("rag.citation.coverage"), null),
                        asDouble(safeMeta.get("citationGate.coverage"), null)),
                firstNonNull(
                        asDouble(safeMeta.get("finalSigmoid"), null),
                        asDouble(safeMeta.get("finalSigmoid.score"), null),
                        asDouble(safeMeta.get("gate.finalSigmoid.score"), null),
                        asDouble(safeMeta.get("finalSigmoidGate.score"), null)),
                failureClass,
                disabledReason);
        return isEmptyPipelineSnapshot(snapshot) ? null : snapshot;
    }

    static ChatStreamEvent.DebugFxSignal buildDebugFxSignal(
            Map<String, Object> meta,
            ChatStreamEvent.TraceSignal traceSignal,
            ChatStreamEvent.PipelineSnapshot pipelineSnapshot) {
        Map<String, Object> safeMeta = meta == null ? Map.of() : meta;
        String copilotReason = debugCopilotReason(safeMeta);
        String reason = firstNonBlank(
                traceSignal == null ? null : traceSignal.reasonCode(),
                pipelineSnapshot == null ? null : pipelineSnapshot.disabledReason(),
                pipelineSnapshot == null ? null : pipelineSnapshot.failureClass(),
                copilotReason,
                safeString(safeMeta.get("failureClass")),
                safeString(safeMeta.get("reasonCode")));
        String phase = firstNonBlank(
                pipelineSnapshot == null ? null : pipelineSnapshot.route(),
                safeString(safeMeta.get("orch.mode")),
                "pipeline");
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        labels.put("answerMode", pipelineSnapshot == null ? null : pipelineSnapshot.answerMode());
        labels.put("failureClass", pipelineSnapshot == null ? null : pipelineSnapshot.failureClass());
        labels.put("disabledReason", pipelineSnapshot == null ? null : pipelineSnapshot.disabledReason());
        labels.put("traceIdHash", traceSignal == null ? null : traceSignal.traceIdHash());
        labels.put("debugCause", copilotReason);
        labels.put("localLlmTriggerReason", safeString(safeMeta.get("llm.localSmoke.operatorAction.triggerReason")));
        labels.put("localLlmFailureClass", safeString(safeMeta.get("llm.localSmoke.operatorAction.failureClass")));
        labels.put("localLlmNextAction", safeString(safeMeta.get("llm.localSmoke.operatorAction.nextAction")));
        labels.put("localLlmActionScore", safeString(safeMeta.get("llm.localSmoke.operatorAction.actionScore")));
        labels.put("localLlmScoreDelta", safeString(safeMeta.get("llm.localSmoke.operatorAction.scoreDelta")));
        labels.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
        return new ChatStreamEvent.DebugFxSignal(
                phase,
                firstNonBlank(reason, "ok"),
                reason == null ? "diagnostics" : "resilience",
                reason == null ? "pipeline diagnostics updated" : "pipeline resilience signal updated",
                null,
                labels);
    }

    static List<ChatStreamEvent.TransformerBlockSignal> buildTransformerBlocks(
            Map<String, Object> meta,
            ChatStreamEvent.StatusSignal statusSignal,
            ChatStreamEvent.PipelineSnapshot pipelineSnapshot,
            ChatStreamEvent.TraceSignal traceSignal,
            ChatStreamEvent.ScoreDeltaSignal scoreDeltaSignal,
            ChatStreamEvent.DebugFxSignal debugFxSignal) {
        String streamCode = statusSignal == null ? null : statusSignal.code();
        String streamStatus = streamStatus(streamCode);
        boolean complete = "complete".equalsIgnoreCase(streamCode) || "final".equalsIgnoreCase(streamCode);
        boolean error = "error".equalsIgnoreCase(streamCode) || "cancelled".equalsIgnoreCase(streamCode);
        String plan = pipelineSnapshot == null ? null : pipelineSnapshot.planId();
        String route = pipelineSnapshot == null ? null : pipelineSnapshot.route();
        Integer web = pipelineSnapshot == null ? null : pipelineSnapshot.webCount();
        Integer vector = pipelineSnapshot == null ? null : pipelineSnapshot.vectorCount();
        Integer context = pipelineSnapshot == null ? null : pipelineSnapshot.finalContextCount();
        String failure = firstNonBlank(
                traceSignal == null ? null : traceSignal.failureClass(),
                pipelineSnapshot == null ? null : pipelineSnapshot.failureClass());
        String disabledReason = pipelineSnapshot == null ? null : pipelineSnapshot.disabledReason();
        String debugReason = debugFxSignal == null ? null : debugFxSignal.code();
        String copilotReason = debugCopilotReason(meta);
        String failureTag = firstFailureTag(meta);
        boolean richDebug = hasAdvancedCoreDebug(meta);

        if (richDebug) {
            List<ChatStreamEvent.TransformerBlockSignal> blocks = new ArrayList<>();
            blocks.add(block("intake", "Intake", "stream", streamStatus,
                    firstNonBlank(streamCode, "stream"), 0, statusSignal == null ? null : statusSignal.tookMs()));
            blocks.add(block("plan", "Plan / MoE", "orchestration",
                    firstNonBlank(plan, route) == null ? (complete ? "skipped" : "queued") : "done",
                    firstNonBlank(plan, route, complete ? "not-measured" : "auto"), 1, null));
            if (queryRewriteObserved(meta)) {
                blocks.add(block("rewrite", "Query Rewrite", "query",
                        queryRewriteStatus(meta, complete),
                        queryRewriteReason(meta), 2, null));
            }
            blocks.add(block("anchor", "Anchor", "compression",
                    anchorStatus(meta, complete),
                    anchorReason(meta), 2, null));
            blocks.add(block("retrieve", "Retrieve", "rag",
                    countPositive(web) || countPositive(vector) ? "done" : (complete ? "skipped" : "queued"),
                    String.format("web:%d-vector:%d", web == null ? 0 : web, vector == null ? 0 : vector), 3, null));
            blocks.add(block("rerank", "DPP / Rerank", "rerank",
                    rerankStatus(meta, complete),
                    rerankReason(meta), 4, null));
            blocks.add(block("compose", "Context", "prompt",
                    countPositive(context) ? "done" : (complete ? "skipped" : "queued"),
                    context == null ? "context-pending" : String.format("context:%d", context), 5, null));
            blocks.add(block("model", "Model", "llm",
                    modelStatus(meta, error, complete, statusSignal == null ? null : statusSignal.tookMs()),
                    modelReason(meta, statusSignal == null ? null : statusSignal.tookMs()),
                    6, statusSignal == null ? null : statusSignal.tookMs()));
            blocks.add(block("cfvm", "CFVM Failure", "memory",
                    cfvmStatus(meta, complete),
                    cfvmReason(meta), 7, null));
            blocks.add(block("recover", "Resilience", "recovery",
                    firstNonBlank(failure, disabledReason, debugReason, copilotReason, failureTag) == null ? "done" : "warn",
                    firstNonBlank(failure, disabledReason, debugReason, copilotReason, failureTag, "ok"), 8, null));
            blocks.add(block("supabase", "Supabase", "external",
                    supabaseStatus(meta, complete),
                    supabaseReason(meta), 9, null));
            return List.copyOf(blocks);
        }

        return List.of(
                block("intake", "Intake", "stream", streamStatus,
                        firstNonBlank(streamCode, "stream"), 0, statusSignal == null ? null : statusSignal.tookMs()),
                block("route", "MoE Route", "orchestration",
                        firstNonBlank(plan, route) == null ? (complete ? "skipped" : "queued") : "done",
                        firstNonBlank(plan, route, complete ? "not measured" : "auto"), 1, null),
                block("retrieve", "Retrieve", "rag",
                        countPositive(web) || countPositive(vector) ? "done" : (complete ? "skipped" : "queued"),
                        String.format("web=%d vector=%d", web == null ? 0 : web, vector == null ? 0 : vector), 2, null),
                block("compose", "Context", "prompt",
                        countPositive(context) ? "done" : (complete ? "skipped" : "queued"),
                        context == null ? "context pending" : String.format("context=%d", context), 3, null),
                block("model", "Model", "llm",
                        error || slowModel(statusSignal == null ? null : statusSignal.tookMs())
                                ? "warn"
                                : (complete ? "done" : "running"),
                        modelReason(meta, statusSignal == null ? null : statusSignal.tookMs()),
                        4, statusSignal == null ? null : statusSignal.tookMs()),
                block("recover", "Resilience", "recovery",
                        firstNonBlank(failure, disabledReason, debugReason, copilotReason, failureTag) == null ? "done" : "warn",
                        firstNonBlank(failure, disabledReason, debugReason, copilotReason, failureTag, "ok"), 5, null));
    }

    private static boolean hasAdvancedCoreDebug(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return false;
        }
        return hasAnyPrefix(meta, "overdrive.", "rag.anchor.", "anchor.", "dpp.", "hypernova.dpp", "rerank.",
                "llm.fastBail", "llm.error.", "llm.endpoint.", "llm.modelGuard.", "llm.model.policy.",
                "llm.defaultModel.", "llm.client.", "llm.ollamaNative.", "llm.gateway.route.", "modelGuard.",
                "cfvm.", "supabase.", "failureTags", "dbg.copilot.", "queryTransformer.subQueries.superTokens.");
    }

    private static boolean hasAnyPrefix(Map<String, Object> meta, String... prefixes) {
        if (meta == null || prefixes == null) {
            return false;
        }
        for (String key : meta.keySet()) {
            if (key == null) {
                continue;
            }
            for (String prefix : prefixes) {
                if (prefix != null && key.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String anchorStatus(Map<String, Object> meta, boolean complete) {
        if (truthy(value(meta, "overdrive.activated"))
                || truthy(value(meta, "overdrive.triggered"))
                || truthy(value(meta, "rag.anchor.enabled"))
                || numberAsInt(value(meta, "overdrive.stagesApplied"), 0) > 0
                || numberAsInt(value(meta, "overdrive.anchor.narrowed.k"), 0) > 0
                || numberAsInt(value(meta, "rag.anchor.acceptedCandidateCount"), 0) > 0) {
            return "done";
        }
        if (firstNonBlank(
                safeString(value(meta, "overdrive.anchor.error")),
                safeString(value(meta, "overdrive.bypassReason")),
                safeString(value(meta, "overdrive.blackbox.disabledReason"))) != null) {
            return "warn";
        }
        return complete ? "skipped" : "queued";
    }

    private static String anchorReason(Map<String, Object> meta) {
        return firstNonBlank(
                safeString(value(meta, "rag.anchor.reason")),
                safeString(value(meta, "overdrive.anchor.narrowedReason")),
                safeString(value(meta, "overdrive.reason")),
                safeString(value(meta, "overdrive.skipReason")),
                safeString(value(meta, "overdrive.bypassReason")),
                countReason("candidates", value(meta, "overdrive.finalCandidateCount")),
                "anchor-pending");
    }

    private static boolean queryRewriteObserved(Map<String, Object> meta) {
        return truthy(value(meta, "queryTransformer.subQueries.superTokens.enabled"))
                || numberAsInt(value(meta, "queryTransformer.subQueries.superTokens.branchCount"), 0) > 0
                || numberAsInt(value(meta, "queryTransformer.subQueries.superTokens.tokenCount"), 0) > 0
                || numberAsInt(value(meta, "queryTransformer.subQueries.superTokens.subModelCount"), 0) > 0
                || numberAsInt(value(meta, "queryTransformer.subQueries.superTokens.branchTitleCount"), 0) > 0
                || truthy(value(meta, "queryTransformer.subQueries.fallback"))
                || truthy(value(meta, "queryTransformer.subQueries.refined"));
    }

    private static String queryRewriteStatus(Map<String, Object> meta, boolean complete) {
        if (queryRewriteObserved(meta)) {
            return "done";
        }
        return complete ? "skipped" : "queued";
    }

    private static String queryRewriteReason(Map<String, Object> meta) {
        String counts = joinNonBlank("_",
                countReason("models", value(meta, "queryTransformer.subQueries.superTokens.subModelCount")),
                countReason("asgn", value(meta, "queryTransformer.subQueries.superTokens.subModelAssignmentCount")),
                countReason("titles", value(meta, "queryTransformer.subQueries.superTokens.branchTitleCount")),
                countReason("title-counts", firstNonNull(
                        value(meta, "queryTransformer.subQueries.superTokens.branchTitleHashCount"),
                        branchTitleHashCount(value(meta, "queryTransformer.subQueries.superTokens.branchTitleHashes")))),
                countReason("supers", value(meta, "queryTransformer.subQueries.superTokens.tokenCount")),
                countReason("branches", value(meta, "queryTransformer.subQueries.superTokens.branchCount")),
                countReason("axes", firstNonNull(
                        value(meta, "queryTransformer.subQueries.superTokens.axisCount"),
                        branchAxisCount(value(meta, "queryTransformer.subQueries.superTokens.axes")))),
                countReason("padded", value(meta, "queryTransformer.subQueries.refined.paddedCount")));
        return firstNonBlank(
                counts,
                truthy(value(meta, "queryTransformer.subQueries.superTokens.titlePresent")) ? "title-present" : null,
                safeString(value(meta, "queryTransformer.subQueries.superTokens.reason")),
                safeString(value(meta, "queryTransformer.subQueries.fallback.reason")),
                safeString(value(meta, "queryTransformer.subQueries.refined.reason")),
                "rewrite-observed");
    }

    private static Integer branchAxisCount(Object value) {
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object item : iterable) {
                String axis = safeString(item);
                if ("definition".equals(axis) || "alias".equals(axis) || "relation".equals(axis)) {
                    count++;
                }
            }
            return count > 0 ? count : null;
        }
        String axis = safeString(value);
        return ("definition".equals(axis) || "alias".equals(axis) || "relation".equals(axis)) ? 1 : null;
    }

    private static Integer branchTitleHashCount(Object value) {
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object item : iterable) {
                if (isHash12(item)) {
                    count++;
                }
            }
            return count > 0 ? count : null;
        }
        return isHash12(value) ? 1 : null;
    }

    private static boolean isHash12(Object value) {
        if (value == null) {
            return false;
        }
        return String.valueOf(value).trim().matches("[a-f0-9]{12}");
    }

    private static String rerankStatus(Map<String, Object> meta, boolean complete) {
        if (truthy(value(meta, "hypernova.dppApplied"))
                || truthy(value(meta, "cihRag.dppApplied"))
                || truthy(value(meta, "rerank.onnx.orchestrator.executed"))
                || numberAsInt(value(meta, "dpp.rerank.outputCount"), 0) > 0
                || numberAsInt(value(meta, "selfask.branchQuality.dpp.outputCount"), 0) > 0) {
            return "done";
        }
        if (truthy(value(meta, "rag.orchestrator.suppressed.dpp"))
                || truthy(value(meta, "dpp.rerank.skipped"))
                || truthy(value(meta, "selfask.branchQuality.dpp.failSoft"))
                || firstNonBlank(
                safeString(value(meta, "hypernova.dppDisabledReason")),
                safeString(value(meta, "cihRag.dppDisabledReason")),
                safeString(value(meta, "rerank.onnx.orchestrator.failureClass"))) != null) {
            return "warn";
        }
        return complete ? "skipped" : "queued";
    }

    private static String rerankReason(Map<String, Object> meta) {
        return firstNonBlank(
                countReason("selected", value(meta, "dpp.rerank.outputCount")),
                countReason("selected", value(meta, "hypernova.dppOutputCount")),
                countReason("selected", value(meta, "selfask.branchQuality.dpp.outputCount")),
                countReason("selected", value(meta, "rerank.onnx.orchestrator.selectedCount")),
                safeString(value(meta, "hypernova.dppDisabledReason")),
                safeString(value(meta, "cihRag.dppDisabledReason")),
                safeString(value(meta, "dpp.rerank.skipReason")),
                safeString(value(meta, "rerank.onnx.orchestrator.failureClass")),
                "rerank-pending");
    }

    private static String cfvmStatus(Map<String, Object> meta, boolean complete) {
        if (firstNonBlank(
                safeString(value(meta, "cfvm.failureRecorder")),
                safeString(value(meta, "cfvm.record.failureClass")),
                safeString(value(meta, "cfvm.record.skipReason")),
                safeString(value(meta, "cfvm.retrievalOrderDisabledReason"))) != null
                || truthy(value(meta, "cfvm.triggered"))
                || truthy(value(meta, "cfvm.record.bufferFailed"))
                || truthy(value(meta, "cfvm.record.memoryFailed"))) {
            return "warn";
        }
        if (numberAsInt(value(meta, "cfvm.boltzmannTemp"), -1) >= 0
                || numberAsInt(value(meta, "cfvm.rawTile.slotCount"), 0) > 0
                || truthy(value(meta, "cfvm.rawTile.enabled"))
                || truthy(value(meta, "cfvm.snapshot.saved"))) {
            return "done";
        }
        return complete ? "skipped" : "queued";
    }

    private static String cfvmReason(Map<String, Object> meta) {
        return firstNonBlank(
                safeString(value(meta, "cfvm.failureRecorder")),
                safeString(value(meta, "cfvm.record.failureClass")),
                safeString(value(meta, "cfvm.record.skipReason")),
                safeString(value(meta, "cfvm.retrievalOrderDisabledReason")),
                safeString(value(meta, "cfvm.tempSource")),
                countReason("tile", value(meta, "cfvm.activeTile")),
                "cfvm-pending");
    }

    private static String supabaseStatus(Map<String, Object> meta, boolean complete) {
        if (firstNonBlank(
                safeString(value(meta, "supabase.evidenceNeeded")),
                safeString(value(meta, "supabase.projectRefMissing")),
                safeString(value(meta, "supabase.disabledReason"))) != null) {
            return "warn";
        }
        if (truthy(value(meta, "supabase.readOnly"))
                || truthy(value(meta, "supabase.projectScoped"))
                || truthy(value(meta, "supabase.connected"))) {
            return "done";
        }
        return complete ? "skipped" : "queued";
    }

    private static String supabaseReason(Map<String, Object> meta) {
        return firstNonBlank(
                safeString(value(meta, "supabase.evidenceNeeded")),
                safeString(value(meta, "supabase.projectRefMissing")),
                safeString(value(meta, "supabase.disabledReason")),
                truthy(value(meta, "supabase.readOnly")) ? "read-only" : null,
                "evidence-needed");
    }

    private static String modelStatus(Map<String, Object> meta, boolean streamError, boolean complete, Long tookMs) {
        if (streamError
                || llmRouteHealthDegraded(meta)
                || truthy(value(meta, "llm.client.blank"))
                || truthy(value(meta, "llm.client.failed"))
                || truthy(value(meta, "llm.call.blank"))
                || truthy(value(meta, "llm.output.blank"))
                || truthy(value(meta, "llm.fastBailTimeout"))
                || truthy(value(meta, "llm.endpoint.compat.mismatch"))
                || truthy(value(meta, "llm.modelGuard.triggered"))
                || truthy(value(meta, "llm.model.policy.blocked"))
                || firstNonBlank(
                safeString(value(meta, "llm.error.code")),
                safeString(value(meta, "llm.modelGuard.failReason")),
                safeString(value(meta, "llm.endpoint.compat.hint")),
                safeString(value(meta, "llm.endpoint.compat.detail"))) != null) {
            return "warn";
        }
        if (complete && slowModel(tookMs)) {
            return "warn";
        }
        if (firstNonBlank(
                safeString(value(meta, "llm.endpoint.compat.healedBy")),
                safeString(value(meta, "llm.call.modelHash")),
                safeString(value(meta, "llm.config.modelHash"))) != null) {
            return complete ? "done" : "running";
        }
        return complete ? "done" : "running";
    }

    private static String modelReason(Map<String, Object> meta, Long tookMs) {
        return firstNonBlank(
                llmRouteHealthReason(meta),
                truthy(value(meta, "llm.client.blank")) ? "client-blank" : null,
                truthy(value(meta, "llm.client.failed"))
                        ? firstNonBlank(safeString(value(meta, "llm.client.errorType")), "client-failed")
                        : null,
                truthy(value(meta, "llm.call.blank")) || truthy(value(meta, "llm.output.blank")) ? "blank-response" : null,
                truthy(value(meta, "llm.fastBailTimeout"))
                        ? countReason("timeout-fast-bail", value(meta, "llm.fastBailTimeout.timeoutHits"))
                        : null,
                truthy(value(meta, "llm.defaultModel.waitStatus"))
                        ? firstNonBlank(safeString(value(meta, "llm.defaultModel.waitStatus.code")), "waiting-for-default-model")
                        : null,
                safeString(value(meta, "llm.modelGuard.failReason")),
                safeString(value(meta, "llm.endpoint.compat.hint")),
                safeString(value(meta, "llm.endpoint.compat.detail")),
                safeString(value(meta, "llm.error.code")),
                safeString(value(meta, "llm.model.policy.blocked.reason")),
                slowModel(tookMs) ? countReason("slow-model-ms", tookMs) : null,
                safeString(value(meta, "llm.endpoint.compat.healedBy")),
                safeString(value(meta, "llm.model")),
                "model-pending");
    }

    private static boolean slowModel(Long tookMs) {
        return tookMs != null && tookMs >= SLOW_MODEL_TOOK_MS;
    }

    private static boolean llmRouteHealthDegraded(Map<String, Object> meta) {
        double pressure = asDouble(value(meta, "llm.gateway.route.healthFailurePressure"), 0.0d);
        String hint = safeString(value(meta, "llm.gateway.route.healthRoutingHint"));
        return pressure >= 0.50d || "llm_route_degrade".equalsIgnoreCase(hint);
    }

    private static String llmRouteHealthReason(Map<String, Object> meta) {
        if (!llmRouteHealthDegraded(meta)) {
            return null;
        }
        return firstNonBlank(
                safeString(value(meta, "llm.gateway.route.healthRoutingHint")),
                "llm-health-pressure");
    }

    private static String firstFailureTag(Map<String, Object> meta) {
        Object tags = value(meta, "failureTags");
        if (tags instanceof Collection<?> collection) {
            for (Object tag : collection) {
                String safeTag = safeFailureTag(tag);
                if (safeTag != null) {
                    return safeTag;
                }
            }
            return null;
        }
        return safeFailureTag(tags);
    }

    private static String safeFailureTag(Object tag) {
        if (tag == null) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(tag, null);
    }

    private static String debugCopilotReason(Map<String, Object> meta) {
        Object causes = value(meta, "dbg.copilot.causes");
        if (causes instanceof Collection<?> collection) {
            for (Object cause : collection) {
                String reason = debugCopilotCauseReason(cause);
                if (reason != null) {
                    return reason;
                }
            }
            return null;
        }
        return debugCopilotCauseReason(causes);
    }

    private static String debugCopilotCauseReason(Object cause) {
        if (cause instanceof Map<?, ?> row) {
            return SafeRedactor.traceLabelOrFallback(firstNonBlank(
                    safeString(row.get("id")),
                    safeString(row.get("title"))), null);
        }
        return safeFailureTag(cause);
    }

    private static String countReason(String label, Object value) {
        int n = numberAsInt(value, -1);
        if (n < 0) {
            return null;
        }
        String safeLabel = SafeRedactor.traceLabelOrFallback(label, "count");
        return safeLabel + ":" + n;
    }

    private static String joinNonBlank(String delimiter, String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        return String.join(delimiter == null ? " " : delimiter, out);
    }

    private static Object value(Map<String, Object> meta, String key) {
        return meta == null ? null : meta.get(key);
    }

    static ChatStreamEvent.PipelineSnapshot withTraceTurnId(
            ChatStreamEvent.PipelineSnapshot snapshot,
            String answerMode,
            Long traceTurnId) {
        if (snapshot == null) {
            return buildPipelineSnapshot(null, answerMode, traceTurnId, null);
        }
        return new ChatStreamEvent.PipelineSnapshot(
                snapshot.planId(),
                snapshot.route(),
                firstNonBlank(answerMode, snapshot.answerMode()),
                traceTurnId,
                snapshot.webCount(),
                snapshot.vectorCount(),
                snapshot.finalContextCount(),
                snapshot.citationCoverage(),
                snapshot.finalSigmoid(),
                snapshot.failureClass(),
                snapshot.disabledReason());
    }

    @SuppressWarnings("unchecked")
    static ChatStreamEvent.ScoreDeltaSignal buildScoreDeltaSignal(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        Object obj = meta.get("ablation.scoreDelta.latest");
        if (!(obj instanceof Map<?, ?>)) {
            obj = meta.get("ablation.traceAnchor.top");
        }
        if (!(obj instanceof Map<?, ?> m)) {
            return null;
        }
        Map<Object, Object> row = (Map<Object, Object>) m;
        Double scoreDelta = doubleFromMap(row, "scoreDelta", "delta");
        Double dropRatio = doubleFromMap(row, "dropRatio");
        Double maxDrawdown = doubleFromMap(row, "maxDrawdown");
        if (maxDrawdown == null) {
            maxDrawdown = asDouble(meta.get("ablation.maxDrawdown"), null);
        }
        Double expectedDelta = doubleFromMap(row, "expectedDelta");
        if (expectedDelta == null) {
            expectedDelta = asDouble(meta.get("ablation.expectedDelta.max"), null);
        }
        Double rawScoreDelta = doubleFromMap(row, "rawScoreDelta", "delta");
        if (scoreDelta == null && dropRatio == null && expectedDelta == null && rawScoreDelta == null) {
            return null;
        }
        return new ChatStreamEvent.ScoreDeltaSignal(
                scoreDelta,
                dropRatio,
                maxDrawdown,
                expectedDelta,
                rawScoreDelta,
                safeString(row.get("clampName")),
                firstNonBlank(safeString(row.get("stage")), safeString(row.get("step"))),
                safeString(row.get("guard")),
                asLong(row.get("eventId")));
    }

    private static boolean isEmptyPipelineSnapshot(ChatStreamEvent.PipelineSnapshot snapshot) {
        return snapshot == null
                || (snapshot.planId() == null
                && snapshot.route() == null
                && snapshot.answerMode() == null
                && snapshot.traceTurnId() == null
                && snapshot.webCount() == null
                && snapshot.vectorCount() == null
                && snapshot.finalContextCount() == null
                && snapshot.citationCoverage() == null
                && snapshot.finalSigmoid() == null
                && snapshot.failureClass() == null
                && snapshot.disabledReason() == null);
    }

    private static ChatStreamEvent.TransformerBlockSignal block(
            String id,
            String label,
            String phase,
            String status,
            String reason,
            int order,
            Long tookMs) {
        return new ChatStreamEvent.TransformerBlockSignal(id, label, phase, status, reason, order, tookMs);
    }

    private static String streamStatus(String code) {
        if (code == null || code.isBlank()) {
            return "running";
        }
        String lower = code.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("complete") || lower.equals("final")) {
            return "done";
        }
        if (lower.equals("error") || lower.equals("cancelled") || lower.equals("timeout")) {
            return "warn";
        }
        return "running";
    }

    private static boolean countPositive(Integer value) {
        return value != null && value > 0;
    }

    private static Integer collectionSizeOrNull(Object value) {
        int size = collectionSize(value);
        return size == 0 ? null : size;
    }

    private static Integer countValue(Object value) {
        if (value == null) {
            return null;
        }
        return numberAsInt(value, 0);
    }

    private static Map<String, Integer> toIntegerMap(Object value) {
        if (!(value instanceof Map<?, ?> input) || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(entry.getKey()), numberAsInt(entry.getValue(), 0));
            if (out.size() >= 24) {
                break;
            }
        }
        return out;
    }

    private static int collectionSize(Object value) {
        if (value instanceof Collection<?> c) {
            return c.size();
        }
        if (value instanceof Map<?, ?> m) {
            return m.size();
        }
        return 0;
    }

    private static int numberAsInt(Object value, int fallback) {
        Long n = asLong(value);
        if (n == null) {
            return fallback;
        }
        if (n > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (n < 0L) {
            return 0;
        }
        return n.intValue();
    }

    private static Double doubleFromMap(Map<Object, Object> row, String... keys) {
        if (row == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key != null && row.containsKey(key)) {
                Double v = asDouble(row.get(key), null);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static Double asDouble(Object value, Double fallback) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : fallback;
        }
        if (value == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException ignore) {
            traceSuppressed("signal.asDouble", ignore);
            return fallback;
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("signal.asLong", ignore);
            return null;
        }
    }

    private static void traceSuppressed(String stage, RuntimeException failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("chat.stream.signal.suppressed.stage", safeStage);
        TraceStore.put("chat.stream.signal.suppressed.errorType", safeErrorType);
        TraceStore.put("chat.stream.signal.suppressed." + safeStage, true);
        TraceStore.put("chat.stream.signal.suppressed." + safeStage + ".errorType", safeErrorType);
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Chat stream signal numeric fallback stage={0} errorType={1}",
                    safeStage,
                    safeErrorType);
        }
    }

    private static String errorType(RuntimeException failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure.getClass().getSimpleName();
    }

    private static String hashIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String s = value.trim();
        if (s.startsWith("hash:")) {
            return s;
        }
        return SafeRedactor.hashValue(s);
    }

    private static String providerCancellationFailureClass(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        for (String provider : java.util.List.of("naver", "brave", "serpapi", "tavily")) {
            if (truthy(meta.get("web." + provider + ".cancelled"))
                    || "cancelled".equalsIgnoreCase(String.valueOf(meta.get("web." + provider + ".exceptionType")))) {
                return "cancelled";
            }
        }
        return null;
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String safeString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).replace('\n', ' ').replace('\r', ' ').trim();
        return s.isBlank() ? null : SafeRedactor.redact(s);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
