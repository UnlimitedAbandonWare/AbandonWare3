// src/main/java/com/example/lms/service/ChatHistoryService.java
package com.example.lms.service;

import com.example.lms.domain.ChatSession;
import java.util.List;
import java.util.Optional;
import com.example.lms.domain.enums.MemoryProfile;




public interface ChatHistoryService {

    record ConversationMemorySnapshot(
            Long lastMessageId,
            String summary,
            List<String> anchors,
            List<String> importantSentences,
            int turns,
            int sentenceCount,
            int tokenEstimate,
            int rawCharCount,
            int compressedCharCount,
            double compressionRatio,
            boolean promoted,
            String promotionHash) {

        public static ConversationMemorySnapshot empty() {
            return new ConversationMemorySnapshot(null, "", List.of(), List.of(), 0, 0, 0, 0, 0, 1.0d, false, "");
        }

        public boolean hasMemory() {
            return (summary != null && !summary.isBlank())
                    || (anchors != null && !anchors.isEmpty())
                    || (importantSentences != null && !importantSentences.isEmpty());
        }

        public String shadowSnippet() {
            StringBuilder sb = new StringBuilder();
            if (summary != null && !summary.isBlank()) {
                sb.append("Session summary:\n").append(summary.strip());
            }
            if (anchors != null && !anchors.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append("Anchor keywords: ").append(String.join(", ", anchors));
            }
            if (importantSentences != null && !importantSentences.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append("Important session memory:\n")
                        .append(String.join("\n", importantSentences));
            }
            return sb.toString().strip();
        }
    }

    /**
     * Start a new chat session.  When a user is not authenticated (anonymous)
     * the session is assigned an owner key derived from the provided client
     * IP.  When no client IP is available the owner key falls back to a
     * random UUID.
     *
     * @param firstMessage the initial user message used to derive the session title
     * @param username the authenticated username (or {@code null} / "anonymousUser" for guests)
     * @param clientIp the client IP address extracted from the request; may be null
     * @return the newly created session wrapped in an Optional
     */
    Optional<ChatSession> startNewSession(String firstMessage, String username, String clientIp);

    /**
     * Start a new session while explicitly providing an already resolved ownerKey
     * and an optional MemoryProfile.  This is useful for reactive / SSE flows
     * where the HttpServletRequest is not available any more and the caller
     * has already resolved the owner identity.
     */
    Optional<ChatSession> startNewSession(
            String firstMessage,
            String username,
            String clientIp,
            String preResolvedOwnerKey,
            MemoryProfile memoryProfile);

    void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage);

    void appendMessage(Long sessionId, String role, String content);

    /**
     * Append a message and return the DB-generated message id (turnId) when available.
     *
     * <p>Used for deterministic trace auto-open (traceTurnId targeting) and other
     * UI debugging features that require a stable turn identifier.</p>
     */
    Long appendMessageReturningId(Long sessionId, String role, String content);

    /**
     * Persist the latest answer.mode and traceTurnId snapshot on the session so that:
     * <ul>
     *   <li>session list badges can be restored cross-device (server as source-of-truth)</li>
     *   <li>sidebar badge clicks can open the exact trace panel (turnId targeting)</li>
     * </ul>
     */
    void updateSessionAnswerModeAndTrace(Long sessionId, String answerMode, Long traceTurnId);

    List<ChatSession> getAllSessionsForAdmin();

    List<ChatSession> getSessionsForUser(String username);

    ChatSession getSessionWithMessages(Long id);

    void deleteSession(Long id);

    List<String> getFormattedRecentHistory(Long sessionId, int limit);

    Optional<String> getRollingSummary(Long sessionId);

    default ConversationMemorySnapshot getConversationMemorySnapshot(Long sessionId) {
        return getRollingSummary(sessionId)
                .map(summary -> new ConversationMemorySnapshot(
                        null,
                        summary,
                        List.of(),
                        List.of(),
                        0,
                        0,
                        0,
                        summary.length(),
                        summary.length(),
                        1.0d,
                        false,
                        ""))
                .orElseGet(ConversationMemorySnapshot::empty);
    }

    void updateRollingSummary(Long sessionId, Long lastMessageId);

    // [NEW] 가장 최근 assistant 메시지
    Optional<String> getLastAssistantMessage(Long sessionId);
}
