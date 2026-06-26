package com.example.lms.service.rag.handler;

import com.example.lms.service.ChatHistoryService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;


/**
 * MemoryHandler - 읽기 전담(minimal):
 *  - loadForSession(sessionId): 최근 N턴을 bullet text로 묶어 프롬프트 주입용 문자열 반환(없으면 null)
 *  - 파일 분리 유지, 다른 체인 단계와의 결합 제거(evidence 주입/핸들러 링크 제거)
 */
@Component
@RequiredArgsConstructor
public class MemoryHandler {
    private static final Logger log = LoggerFactory.getLogger(MemoryHandler.class);

    private final ChatHistoryService historyService;

    @Value("${memory.read.max-turns:8}")
    private int maxTurns;
    /** 프롬프트 주입용: 세션의 최근 N턴을 bullet text로 묶어 반환(없으면 null) */
    public String loadForSession(Long sessionId) {
        try {
            if (sessionId == null) {
                traceRehydrate(false, 0, null, "missing_session");
                return null;
            }
            ChatHistoryService.ConversationMemorySnapshot snapshot =
                    historyService.getConversationMemorySnapshot(sessionId);
            String summary = snapshot.summary();
            List<String> hist = historyService.getFormattedRecentHistory(sessionId, Math.max(1, maxTurns));
            if (hist != null && !hist.isEmpty()) {
                hist = hist.stream()
                        .filter(line -> !isInjectedRollingSummaryLine(line))
                        .toList();
            }
            int recentCount = hist == null ? 0 : hist.size();
            boolean summaryPresent = summary != null && !summary.isBlank();
            traceRehydrate(summaryPresent, recentCount, sessionId, null, snapshot);
            boolean anchorsPresent = snapshot.anchors() != null && !snapshot.anchors().isEmpty();
            boolean importantPresent = snapshot.importantSentences() != null && !snapshot.importantSentences().isEmpty();
            if (!summaryPresent && !anchorsPresent && !importantPresent && recentCount == 0) return null;
            StringBuilder out = new StringBuilder();
            if (summaryPresent) {
                out.append("Conversation summary:\n")
                        .append(summary.strip())
                        .append("\n\n");
            }
            if (anchorsPresent) {
                out.append("Anchor keywords:\n")
                        .append(String.join(", ", snapshot.anchors()))
                        .append("\n\n");
            }
            if (importantPresent) {
                out.append("Important session memory:\n")
                        .append(String.join("\n", snapshot.importantSentences()))
                        .append("\n\n");
            }
            if (recentCount > 0) {
                out.append("Recent turns:\n")
                        .append(String.join("\n", hist));
            }
            return out.toString().strip();
        } catch (Exception e) {
            log.warn("[AWX][rag][handler] memory loadForSession failed failureReason={} errorType={} sessionHash={}",
                    "memory-load-session-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hash12(String.valueOf(sessionId)));
            return null;
        }
    }

    public List<Content> loadRecoveryContents(Long sessionId, int maxItems) {
        try {
            if (sessionId == null) {
                TraceStore.put("memory.recovery.reason", "missing_session");
                TraceStore.put("memory.recovery.count", 0);
                return List.of();
            }
            int limit = Math.max(1, maxItems);
            ChatHistoryService.ConversationMemorySnapshot snapshot =
                    historyService.getConversationMemorySnapshot(sessionId);
            List<String> hist = historyService.getFormattedRecentHistory(sessionId, Math.max(1, maxTurns));
            if (hist != null && !hist.isEmpty()) {
                hist = hist.stream()
                        .filter(line -> !isInjectedRollingSummaryLine(line))
                        .toList();
            }

            List<Content> out = new ArrayList<>(limit);
            if (snapshot != null && out.size() < limit) {
                String sessionMemory = recoverySessionMemoryText(snapshot);
                if (sessionMemory != null && !sessionMemory.isBlank()) {
                    out.add(recoveryContent(
                            "session_memory",
                            sessionMemory,
                            sessionId,
                            snapshot.anchors() == null ? 0 : snapshot.anchors().size(),
                            snapshot.sentenceCount()));
                }
            }
            if (hist != null && !hist.isEmpty() && out.size() < limit) {
                String recent = String.join("\n", hist);
                out.add(recoveryContent(
                        "conversation_history",
                        recent,
                        sessionId,
                        0,
                        hist.size()));
            }

            TraceStore.put("memory.recovery.count", out.size());
            TraceStore.put("memory.recovery.summaryPresent",
                    snapshot != null && snapshot.summary() != null && !snapshot.summary().isBlank());
            TraceStore.put("memory.recovery.recentTurnCount", hist == null ? 0 : hist.size());
            return out;
        } catch (Exception e) {
            TraceStore.put("memory.recovery.count", 0);
            TraceStore.put("memory.recovery.reason",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"));
            log.warn("[AWX][rag][handler] memory loadRecoveryContents failed failureReason={} errorType={} sessionHash={} maxItems={}",
                    "memory-recovery-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hash12(String.valueOf(sessionId)),
                    maxItems);
            return List.of();
        }
    }

    private static String recoverySessionMemoryText(ChatHistoryService.ConversationMemorySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        if (snapshot.summary() != null && !snapshot.summary().isBlank()) {
            out.append(snapshot.summary().strip()).append("\n");
        }
        if (snapshot.importantSentences() != null && !snapshot.importantSentences().isEmpty()) {
            for (String sentence : snapshot.importantSentences()) {
                if (sentence != null && !sentence.isBlank()) {
                    out.append(sentence.strip()).append("\n");
                }
            }
        }
        if (snapshot.anchors() != null && !snapshot.anchors().isEmpty()) {
            out.append("Anchors: ").append(String.join(", ", snapshot.anchors())).append("\n");
        }
        return limit(out.toString().strip(), 1600);
    }

    private static Content recoveryContent(String source,
                                           String text,
                                           Long sessionId,
                                           int anchorCount,
                                           int sentenceCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", source);
        metadata.put("_retrieval_recovery", "true");
        metadata.put("sidHash", SafeRedactor.hash12(String.valueOf(sessionId)));
        metadata.put("anchorCount", String.valueOf(Math.max(0, anchorCount)));
        metadata.put("sentenceCount", String.valueOf(Math.max(0, sentenceCount)));
        return Content.from(TextSegment.from(limit(text, 1600), Metadata.from(metadata)));
    }

    private static String limit(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int max = Math.max(128, maxChars);
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static void traceRehydrate(boolean summaryPresent, int recentTurnCount, Long sessionId, String reason) {
        traceRehydrate(summaryPresent, recentTurnCount, sessionId, reason,
                ChatHistoryService.ConversationMemorySnapshot.empty());
    }

    private static void traceRehydrate(
            boolean summaryPresent,
            int recentTurnCount,
            Long sessionId,
            String reason,
            ChatHistoryService.ConversationMemorySnapshot snapshot) {
        try {
            TraceStore.put("memory.rehydrate.summaryPresent", summaryPresent);
            TraceStore.put("memory.rehydrate.recentTurnCount", Math.max(0, recentTurnCount));
            if (sessionId != null) {
                TraceStore.put("memory.rehydrate.sessionHash", SafeRedactor.hash12(String.valueOf(sessionId)));
            }
            if (reason != null && !reason.isBlank()) {
                TraceStore.put("memory.rehydrate.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            }
            if (snapshot != null) {
                TraceStore.put("memory.session.turnCount", snapshot.turns());
                TraceStore.put("memory.session.sentenceCount", snapshot.sentenceCount());
                TraceStore.put("memory.session.tokenEstimate", snapshot.tokenEstimate());
                TraceStore.put("memory.session.compressionRatio", snapshot.compressionRatio());
                TraceStore.put("memory.session.anchorCount",
                        snapshot.anchors() == null ? 0 : snapshot.anchors().size());
                TraceStore.put("memory.session.anchorHashes",
                        snapshot.anchors() == null
                                ? List.of()
                                : snapshot.anchors().stream().map(SafeRedactor::hash12).toList());
                TraceStore.put("memory.session.promoted", snapshot.promoted());
            }
        } catch (Throwable ignore) {
            RetrievalHandlerTraceSuppressions.traceSuppressed(log, "MemoryHandler", "memory.rehydrate.trace", ignore);
        }
    }

    private static boolean isInjectedRollingSummaryLine(String line) {
        return line != null && line.stripLeading().startsWith("System: (Rolling Summary)");
    }

}
