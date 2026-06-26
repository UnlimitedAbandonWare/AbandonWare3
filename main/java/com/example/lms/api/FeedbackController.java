package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.FeedbackDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.web.ClientOwnerKeyResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class FeedbackController {
    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final MemoryReinforcementService memoryService;
    private final ChatHistoryService historyService;
    private final ClientOwnerKeyResolver ownerKeyResolver;

    @PostMapping("/feedback")
    public ResponseEntity<?> feedback(@RequestBody FeedbackDto req) {
        if (req == null) {
            return ResponseEntity.badRequest().body("missing_feedback");
        }
        ResponseEntity<?> denied = authorizeFeedbackSession(req.sessionId());
        if (denied != null) {
            return denied;
        }
        try {
            boolean positive = "POSITIVE".equalsIgnoreCase(req.rating());
            memoryService.applyFeedback(
                    String.valueOf(req.sessionId()),
                    req.message(),
                    positive,
                    req.corrected()
            );
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("[AWX][feedback] failed type={} errorHash={} errorLength={}",
                    e.getClass().getSimpleName(),
                    SafeRedactor.hashValue(messageOf(e)),
                    messageLength(e));
            return ResponseEntity.badRequest().body(publicFeedbackError(e));
        }
    }

    static String publicFeedbackError(Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        return "feedback error: errorCode=feedback_failed"
                + " errorHash=" + SafeRedactor.hashValue(message)
                + " errorLength=" + message.length();
    }

    private ResponseEntity<?> authorizeFeedbackSession(Long sessionId) {
        if (sessionId == null) {
            traceFeedbackRejected("missing_session", null);
            return ResponseEntity.badRequest().body("missing_session");
        }
        ChatSession session;
        try {
            session = historyService.getSessionWithMessages(sessionId);
        } catch (Exception e) {
            log.warn("[AWX][feedback] session authorization failed sessionHash={} errorType={}",
                    SafeRedactor.hashValue(String.valueOf(sessionId)),
                    e.getClass().getSimpleName());
            traceFeedbackRejected("session_forbidden", sessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("session_forbidden");
        }
        if (session == null) {
            traceFeedbackRejected("session_not_found", sessionId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("session_not_found");
        }
        if (session.getAdministrator() != null) {
            traceFeedbackRejected("session_forbidden", sessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("session_forbidden");
        }
        String currentOwnerKey = ownerKeyResolver.ownerKey();
        if (session.getOwnerKey() == null || !session.getOwnerKey().equals(currentOwnerKey)) {
            traceFeedbackRejected("session_forbidden", sessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("session_forbidden");
        }
        return null;
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message.length();
    }

    private static void traceFeedbackRejected(String reason, Long sessionId) {
        TraceStore.put("api.feedback.rejected", true);
        TraceStore.put("api.feedback.skipped.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        TraceStore.put("api.feedback.sessionHash",
                sessionId == null ? "" : SafeRedactor.hashValue(String.valueOf(sessionId)));
    }
}
