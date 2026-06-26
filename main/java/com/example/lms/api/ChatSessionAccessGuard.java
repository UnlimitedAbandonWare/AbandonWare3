package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class ChatSessionAccessGuard {
    private ChatSessionAccessGuard() {
    }

    static ResponseEntity<ChatResponseDto> authorize(
            ChatHistoryService historyService,
            Long sessionId,
            String username,
            String ownerKey,
            Logger log) {
        if (sessionId == null) {
            return null;
        }
        ChatSession session = historyService.getSessionWithMessages(sessionId);
        if (session == null || canAccess(session, username, ownerKey)) {
            return null;
        }
        if (log != null) {
            log.warn("[AWX][chat][session] rejected foreign session sessionHash={}",
                    SafeRedactor.hashValue(String.valueOf(sessionId)));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ChatResponseDto("session_forbidden", sessionId, "forbidden", false));
    }

    private static boolean canAccess(ChatSession session, String username, String ownerKey) {
        var owner = session.getAdministrator();
        if (owner != null) {
            return username != null && owner.getUsername().equals(username);
        }
        return session.getOwnerKey() != null && session.getOwnerKey().equals(ownerKey);
    }
}
