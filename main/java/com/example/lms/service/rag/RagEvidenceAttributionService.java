package com.example.lms.service.rag;

import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.CitationGate;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.guard.EvidenceGate;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import com.example.lms.util.MetadataUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RagEvidenceAttributionService {

    private static final Logger log = LoggerFactory.getLogger(RagEvidenceAttributionService.class);

    private static final Pattern MARKER_PATTERN = Pattern.compile("\\[([WVD])(\\d+)]");
    private static final int MAX_APPENDIX_LINES = 12;

    private final EvidenceGate evidenceGate;
    private final CitationGate citationGate;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;

    public RagEvidenceAttributionService(
            EvidenceGate evidenceGate,
            CitationGate citationGate,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this.evidenceGate = evidenceGate;
        this.citationGate = citationGate;
        this.debugEventStoreProvider = debugEventStoreProvider;
    }

    public List<RagEvidenceMetadata> promoteForPrompt(
            String question,
            List<Content> webDocs,
            List<Content> vectorDocs,
            List<Document> localDocs,
            QueryDomain domain,
            boolean followUp) {
        long started = System.nanoTime();
        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(fromContents("WEB", "W", webDocs));
        candidates.addAll(fromContents("VECTOR", "V", vectorDocs));
        candidates.addAll(fromDocuments("LOCAL_DOC", "D", localDocs));

        List<String> vectorLines = new ArrayList<>();
        List<String> kbLines = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.text == null || candidate.text.isBlank()) {
                continue;
            }
            if ("VECTOR".equals(candidate.metadata.kind())) {
                vectorLines.add(candidate.text);
            } else {
                kbLines.add(candidate.text);
            }
        }

        boolean evidencePassed = false;
        boolean citationSoftPassed = false;
        boolean citationMinPassed = false;
        int citationMin = effectiveMinCitations();
        String reason = null;
        try {
            evidencePassed = evidenceGate == null || evidenceGate.hasSufficientCoverage(
                    question,
                    vectorLines,
                    List.of(),
                    kbLines,
                    followUp,
                    domain == null ? QueryDomain.GENERAL : domain);
            List<Candidate> citableCandidates = citableCandidates(candidates);
            List<String> sources = citableCandidates.stream()
                    .map(c -> locatorKey(c.metadata))
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .toList();
            citationSoftPassed = citationGate == null || citationGate.ok(sources, citationMin, 0.0d);
            citationMinPassed = citationGate == null || sources.size() >= citationMin;
            if (!evidencePassed) {
                reason = "evidence_gate_blocked";
            } else if (citableCandidates.isEmpty()) {
                reason = "no_citable_locator";
            } else if (!citationSoftPassed || !citationMinPassed) {
                reason = "citation_gate_blocked";
            }
        } catch (Throwable ex) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "promotion.gate");
            evidencePassed = false;
            citationSoftPassed = false;
            citationMinPassed = false;
            reason = "gate_exception_" + ex.getClass().getSimpleName();
        }

        List<Candidate> citableCandidates = citableCandidates(candidates);
        List<RagEvidenceMetadata> promoted = (evidencePassed && citationSoftPassed && citationMinPassed
                && !citableCandidates.isEmpty())
                ? citableCandidates.stream().map(Candidate::metadata).toList()
                : List.of();
        long stageMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        recordPromotion(question, candidates, promoted, evidencePassed, citationSoftPassed,
                citationMinPassed, citationMin, reason, stageMs);
        return promoted;
    }

    public String appendFinalEvidenceAppendix(String answer, List<RagEvidenceMetadata> evidence) {
        if (answer == null || answer.isBlank() || evidence == null || evidence.isEmpty()) {
            return answer;
        }
        String trimmed = answer.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("### sources") || lower.contains("### evidence")
                || lower.contains("### references") || lower.contains("### citations")) {
            return answer;
        }

        Set<String> usedMarkers = new LinkedHashSet<>();
        Matcher matcher = MARKER_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            usedMarkers.add(matcher.group(1) + matcher.group(2));
        }

        List<RagEvidenceMetadata> selected;
        if (usedMarkers.isEmpty()) {
            selected = evidence.stream()
                    .filter(e -> e != null && e.marker() != null)
                    .limit(3)
                    .toList();
        } else {
            selected = evidence.stream()
                    .filter(e -> e != null && e.marker() != null)
                    .filter(e -> usedMarkers.contains(e.marker()))
                    .limit(MAX_APPENDIX_LINES)
                    .toList();
            if (selected.size() < usedMarkers.size()) {
                try {
                    TraceStore.put("rag.evidence.appendix.markerMismatch", true);
                    TraceStore.put("rag.evidence.appendix.requestedMarkerCount", usedMarkers.size());
                    TraceStore.put("rag.evidence.appendix.matchedMarkerCount", selected.size());
                } catch (Throwable ignore) {
                    log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "appendix.markerMismatchTrace");
                    RagEvidenceTraceSuppressions.trace("appendix.markerMismatchTrace", ignore);
                }
            }
        }
        if (selected.isEmpty()) {
            return answer;
        }

        try {
            TraceStore.put("rag.evidence.appendix.count", selected.size());
            TraceStore.put("rag.evidence.appendix.mode", usedMarkers.isEmpty() ? "top_passed_no_markers" : "answer_markers");
            appendBreadcrumb("RagEvidenceAttributionService", "answer_appendix", Map.of(
                    "selectedCount", selected.size(),
                    "markerMode", usedMarkers.isEmpty() ? "top_passed_no_markers" : "answer_markers"));
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "appendix.trace");
            RagEvidenceTraceSuppressions.trace("appendix.trace", ignore);
        }

        StringBuilder sb = new StringBuilder(trimmed);
        sb.append("\n\n---\n### Sources\n");
        for (RagEvidenceMetadata item : selected) {
            sb.append("- ").append(formatAppendixLine(item)).append('\n');
        }
        return sb.toString();
    }

    private List<Candidate> fromContents(String kind, String prefix, List<Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        int rank = 0;
        for (Content doc : docs) {
            if (doc == null) {
                continue;
            }
            rank++;
            String text = contentText(doc);
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = contentMetadata(doc);
            RagEvidenceMetadata item = new RagEvidenceMetadata(
                    prefix + rank,
                    kind,
                    sanitizeTitle(firstNonBlank(value(metadata, "title"), value(metadata, "document_title"),
                            value(metadata, "name"), value(metadata, "fileName"), value(metadata, "filename"))),
                    sanitizePublicUrl(firstNonBlank(value(metadata, "url"), value(metadata, "link"), value(metadata, "source"),
                            value(metadata, "uri"), value(metadata, "document_url"))),
                    sanitizeFilePath(firstNonBlank(value(metadata, "filePath"), value(metadata, "file_path"),
                            value(metadata, "source_path"), value(metadata, "path"),
                            value(metadata, "documentPath"), value(metadata, "filename"))),
                    firstInt(metadata, "lineStart", "line_start", "startLine", "start_line", "chunkStartLine", "line"),
                    firstInt(metadata, "lineEnd", "line_end", "endLine", "end_line", "chunkEndLine"),
                    rank,
                    confidence(metadata),
                    confidenceSource(metadata)
            );
            out.add(new Candidate(item, text, SafeRedactor.hash12(text)));
        }
        return out;
    }

    private List<Candidate> fromDocuments(String kind, String prefix, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        int rank = 0;
        for (Document doc : docs) {
            if (doc == null) {
                continue;
            }
            rank++;
            String text;
            try {
                text = doc.text();
            } catch (Throwable ignore) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "document.text");
                text = null;
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> metadata;
            try {
                metadata = MetadataUtils.toMap(doc.metadata());
            } catch (Throwable ignore) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "document.metadata");
                metadata = Map.of();
            }
            RagEvidenceMetadata item = new RagEvidenceMetadata(
                    prefix + rank,
                    kind,
                    sanitizeTitle(firstNonBlank(value(metadata, "title"), value(metadata, "document_title"),
                            value(metadata, "name"), value(metadata, "fileName"), value(metadata, "filename"))),
                    sanitizePublicUrl(firstNonBlank(value(metadata, "source"), value(metadata, "url"), value(metadata, "link"))),
                    sanitizeFilePath(firstNonBlank(value(metadata, "filePath"), value(metadata, "file_path"),
                            value(metadata, "source_path"), value(metadata, "path"),
                            value(metadata, "documentPath"), value(metadata, "filename"))),
                    firstInt(metadata, "lineStart", "line_start", "startLine", "start_line", "chunkStartLine", "line"),
                    firstInt(metadata, "lineEnd", "line_end", "endLine", "end_line", "chunkEndLine"),
                    rank,
                    confidence(metadata),
                    confidenceSource(metadata)
            );
            out.add(new Candidate(item, text, SafeRedactor.hash12(text)));
        }
        return out;
    }

    private void recordPromotion(
            String question,
            List<Candidate> candidates,
            List<RagEvidenceMetadata> promoted,
            boolean evidencePassed,
            boolean citationSoftPassed,
            boolean citationMinPassed,
            int citationMin,
            String reason,
            long stageMs) {
        int candidateCount = candidates == null ? 0 : candidates.size();
        int promotedCount = promoted == null ? 0 : promoted.size();
        List<Candidate> citableCandidates = citableCandidates(candidates);
        int citableLocatorCount = citableCandidates.size();
        long distinctCitableLocatorCount = citableCandidates.stream()
                .map(c -> locatorKey(c.metadata))
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .count();
        List<Map<String, Object>> publicTrace = toTraceList(promoted);
        List<Map<String, Object>> candidateTrace = candidateTrace(candidates);
        double diversity = sourceDiversity(promoted);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("candidateCount", candidateCount);
        decision.put("citableLocatorCount", citableLocatorCount);
        decision.put("distinctCitableLocatorCount", distinctCitableLocatorCount);
        decision.put("promotedCount", promotedCount);
        decision.put("evidenceGatePassed", evidencePassed);
        decision.put("citationGateSoftPassed", citationSoftPassed);
        decision.put("citationGateMinPassed", citationMinPassed);
        decision.put("citationMin", citationMin);
        decision.put("stageMs", stageMs);
        decision.put("sourceDiversity", diversity);
        if (reason != null && !reason.isBlank()) {
            decision.put("disabledReason", reason);
        }

        try {
            TraceStore.put("rag.evidence.promotion.candidateCount", candidateCount);
            TraceStore.put("rag.evidence.promotion.citableLocatorCount", citableLocatorCount);
            TraceStore.put("rag.evidence.promotion.distinctCitableLocatorCount", distinctCitableLocatorCount);
            TraceStore.put("rag.evidence.promotion.promotedCount", promotedCount);
            TraceStore.put("rag.evidence.promotion.evidenceGatePassed", evidencePassed);
            TraceStore.put("rag.evidence.promotion.citationGateSoftPassed", citationSoftPassed);
            TraceStore.put("rag.evidence.promotion.citationGateMinPassed", citationMinPassed);
            TraceStore.put("rag.evidence.promotion.citationMin", citationMin);
            TraceStore.put("rag.evidence.promotion.stageMs", stageMs);
            TraceStore.put("rag.evidence.promotion.sourceDiversity", diversity);
            if (reason != null && !reason.isBlank()) {
                TraceStore.put("rag.evidence.promotion.disabledReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            }
            TraceStore.put("rag.evidence.candidates", candidateTrace);
            TraceStore.put("rag.evidence.public", publicTrace);
            TraceStore.put("prompt.citableEvidenceCount", promotedCount);
            TraceContext.current().setFlag("rag.evidence.promotedCount", promotedCount);
            appendBreadcrumb("RagEvidenceAttributionService", promotedCount > 0 ? "evidence_promoted" : "evidence_blocked",
                    decision);
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "promotion.trace");
            RagEvidenceTraceSuppressions.trace("promotion.trace", ignore);
        }

        try {
            String queryHash = SafeRedactor.hash12(question);
            OrchEventEmitter.ragEvent(
                    "rag.pipeline",
                    "prompt",
                    "evidence_promotion",
                    "complete",
                    "RagEvidenceAttributionService",
                    promotedCount > 0 ? "ok" : "blocked",
                    Map.of(
                            "queryHash", queryHash == null ? "" : queryHash,
                            "queryLen", question == null ? 0 : question.length(),
                            "requestedTopK", candidateCount,
                            "mode", "gate_promote"),
                    Map.of(
                            "returnedCount", candidateCount,
                            "afterFilterCount", candidateCount,
                            "selectedCount", candidateCount,
                            "promotedCount", promotedCount,
                            "stageMs", stageMs,
                            "sourceDiversity", diversity),
                    reason == null ? Map.of() : Map.of(
                            "reasonCode", reason,
                            "failureClass", reason,
                            "exceptionType", "None"),
                    Map.of(
                            "action", promotedCount > 0 ? "promote" : "block",
                            "applied", true,
                            "reasonCode", reason == null ? "passed" : reason,
                            "breadcrumbId", traceBreadcrumbId()));
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "promotion.ragEvent");
            RagEvidenceTraceSuppressions.trace("promotion.ragEvent", ignore);
        }

        emitDebug(decision);
    }

    private static String traceBreadcrumbId() {
        Object traceId = TraceStore.get("trace.id");
        String hash = traceId == null ? null : SafeRedactor.hashValue(String.valueOf(traceId));
        return hash == null || hash.isBlank() ? "evidence_promotion" : hash;
    }

    private void emitDebug(Map<String, Object> decision) {
        try {
            DebugEventStore store = debugEventStoreProvider == null ? null : debugEventStoreProvider.getIfAvailable();
            if (store == null) {
                return;
            }
            store.emit(
                    DebugProbeType.ORCHESTRATION,
                    Boolean.TRUE.equals(decision.get("evidenceGatePassed"))
                            && Boolean.TRUE.equals(decision.get("citationGateMinPassed"))
                            ? DebugEventLevel.INFO : DebugEventLevel.WARN,
                    "rag.evidence.promotion",
                    "RAG evidence promotion evaluated.",
                    "RagEvidenceAttributionService.promoteForPrompt",
                    decision,
                    null);
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "promotion.debugEvent");
            RagEvidenceTraceSuppressions.trace("promotion.debugEvent", ignore);
        }
    }

    private static void appendBreadcrumb(String component, String decision, Map<String, Object> data) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("seq", TraceStore.nextSequence("ml.breadcrumbs.v1"));
        row.put("ts", Instant.now().toString());
        row.put("component", component);
        row.put("rules", "EvidenceGate+CitationGate");
        row.put("decision", decision);
        row.put("requestId", SafeRedactor.hashValue(firstNonBlank(MDC.get("x-request-id"), String.valueOf(TraceStore.get("requestId")))));
        row.put("sessionId", SafeRedactor.hashValue(firstNonBlank(MDC.get("sessionId"), MDC.get("sid"), String.valueOf(TraceStore.get("sessionId")))));
        Map<String, Object> safeData = new LinkedHashMap<>();
        safeData.put("queryRedacted", true);
        if (data != null && !data.isEmpty()) {
            safeData.putAll(data);
        }
        row.put("data", safeData);
        TraceStore.append("ml.breadcrumbs.v1", row);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
    }

    private static List<Map<String, Object>> toTraceList(List<RagEvidenceMetadata> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (RagEvidenceMetadata item : evidence) {
            if (item != null) {
                out.add(item.toTraceMap());
            }
        }
        return out;
    }

    private static List<Map<String, Object>> candidateTrace(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.metadata == null) {
                continue;
            }
            Map<String, Object> row = candidate.metadata.toTraceMap();
            row.put("snippetHash", candidate.snippetHash);
            out.add(row);
        }
        return out;
    }

    private static int effectiveMinCitations() {
        try {
            GuardContext ctx = GuardContextHolder.getOrDefault();
            if (ctx != null && ctx.getMinCitations() != null && ctx.getMinCitations() > 0) {
                return ctx.getMinCitations();
            }
            if (ctx != null && ctx.getMode() != null) {
                return switch (ctx.getMode()) {
                    case "BRAVE" -> 2;
                    case "ZERO_BREAK", "RULE_BREAK" -> 1;
                    default -> 3;
                };
            }
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "effectiveMinCitations");
            RagEvidenceTraceSuppressions.trace("kindRank.parse", ignore);
        }
        return 3;
    }

    private static String formatAppendixLine(RagEvidenceMetadata item) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(item.marker()).append("] ");
        String safeFilePath = safeAppendixFilePath(item.filePath());
        String label = firstNonBlank(item.title(), safeFilePath, item.source(), item.kind());
        sb.append(label == null ? "evidence" : label);
        if (item.source() != null && !item.source().isBlank() && !item.source().equals(label)) {
            sb.append(" - ").append(item.source());
        }
        if (safeFilePath != null && !safeFilePath.isBlank() && !safeFilePath.equals(label)) {
            sb.append(" (file: ").append(safeFilePath);
            if (item.lineStart() != null) {
                sb.append(':').append(item.lineStart());
                if (item.lineEnd() != null && !item.lineEnd().equals(item.lineStart())) {
                    sb.append('-').append(item.lineEnd());
                }
            }
            sb.append(')');
        } else if (item.lineStart() != null) {
            sb.append(" (line ").append(item.lineStart());
            if (item.lineEnd() != null && !item.lineEnd().equals(item.lineStart())) {
                sb.append('-').append(item.lineEnd());
            }
            sb.append(')');
        }
        if (item.confidence() != null) {
            sb.append(" (confidence ")
                    .append(String.format(Locale.ROOT, "%.3f", item.confidence()))
                    .append(')');
        } else if ("unavailable".equals(item.confidenceSource())) {
            sb.append(" (confidence unavailable)");
        }
        return sb.toString();
    }

    private static String safeAppendixFilePath(String filePath) {
        String path = firstNonBlank(filePath);
        if (path == null) {
            return null;
        }
        return "pathHash=" + SafeRedactor.hashValue(path) + " pathLength=" + path.length();
    }

    private static List<Candidate> citableCandidates(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(c -> c != null && hasCitableLocator(c.metadata))
                .toList();
    }

    private static boolean hasCitableLocator(RagEvidenceMetadata metadata) {
        return locatorKey(metadata) != null;
    }

    private static String locatorKey(RagEvidenceMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        String source = firstNonBlank(metadata.source());
        if (source != null) {
            return "url:" + source.toLowerCase(Locale.ROOT);
        }
        String path = firstNonBlank(metadata.filePath());
        if (path != null) {
            return "file:" + path.replace('\\', '/').toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static double sourceDiversity(List<RagEvidenceMetadata> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0d;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (RagEvidenceMetadata item : evidence) {
            if (item == null) {
                continue;
            }
            String key = host(firstNonBlank(item.source(), item.filePath(), item.title()));
            if (key != null && !key.isBlank()) {
                seen.add(key);
            }
        }
        return evidence.isEmpty() ? 0.0d : (double) seen.size() / (double) evidence.size();
    }

    private static String host(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            String h = uri.getHost();
            if (h != null && !h.isBlank()) {
                return h.toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "hostOf.parse");
            RagEvidenceTraceSuppressions.trace("hostOf.parse", ignore);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String contentText(Content content) {
        try {
            if (content != null && content.textSegment() != null && content.textSegment().text() != null) {
                return content.textSegment().text();
            }
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "content.text");
            RagEvidenceTraceSuppressions.trace("content.text", ignore);
        }
        return null;
    }

    private static Map<String, Object> contentMetadata(Content content) {
        try {
            if (content != null && content.textSegment() != null) {
                return MetadataUtils.toMap(content.textSegment().metadata());
            }
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "content.metadata");
            RagEvidenceTraceSuppressions.trace("content.metadata", ignore);
        }
        return Map.of();
    }

    private static String value(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer firstInt(Map<String, Object> metadata, String... keys) {
        if (metadata == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            Integer parsed = toInt(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "toInt");
                return null;
            }
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignore) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "toInt");
                return null;
            }
        }
        return null;
    }

    private static Double confidence(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (String key : confidenceKeys()) {
            Object value = metadata.get(key);
            Double parsed = toDouble(value);
            if (parsed != null) {
                return Math.max(0.0d, Math.min(1.0d, parsed));
            }
        }
        return null;
    }

    private static String confidenceSource(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "unavailable";
        }
        for (String key : confidenceKeys()) {
            if (toDouble(metadata.get(key)) != null) {
                return key;
            }
        }
        return "unavailable";
    }

    private static List<String> confidenceKeys() {
        return List.of(
                "_nova.compositionScore",
                "_nova.rerankConfidence",
                "rerankConfidence",
                "rerank_confidence",
                "grandas_adjusted_score",
                "grandas_base_score",
                "relevanceScore",
                "rerankScore",
                "vectorScore",
                "score");
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (!Double.isFinite(parsed)) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "toDouble");
                return null;
            }
            return parsed;
        }
        if (value instanceof String s) {
            try {
                double parsed = Double.parseDouble(s.trim());
                if (!Double.isFinite(parsed)) {
                    log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "toDouble");
                    return null;
                }
                return parsed;
            } catch (NumberFormatException ignore) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "toDouble");
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim())) {
                return value.trim();
            }
        }
        return null;
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        String s = value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
        if (max > 0 && s.length() > max) {
            return s.substring(0, max);
        }
        return s;
    }

    private static String sanitizeTitle(String value) {
        return limit(SafeRedactor.safeMessage(value, 180), 180);
    }

    private static String sanitizeFilePath(String value) {
        if (value == null) {
            return null;
        }
        String safe = value.replace('\u0000', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (safe.isBlank() || "null".equalsIgnoreCase(safe)) {
            return null;
        }
        return limit(SafeRedactor.redact(safe), 1000);
    }

    private static String sanitizePublicUrl(String value) {
        String raw = firstNonBlank(value);
        if (raw == null) {
            return null;
        }
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            URI clean = new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getRawPath(),
                    null,
                    null);
            return limit(clean.toString(), 1000);
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "sanitizePublicUrl");
            return null;
        }
    }

    private record Candidate(RagEvidenceMetadata metadata, String text, String snippetHash) {
    }
}
