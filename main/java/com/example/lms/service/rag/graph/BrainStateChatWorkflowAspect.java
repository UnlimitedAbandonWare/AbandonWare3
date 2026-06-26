package com.example.lms.service.rag.graph;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 40)
public class BrainStateChatWorkflowAspect {

    private static final Logger log = LoggerFactory.getLogger(BrainStateChatWorkflowAspect.class);

    private final BrainStateProperties properties;
    private final GraphRagChunkingService chunkingService;

    public BrainStateChatWorkflowAspect(BrainStateProperties properties,
                                        GraphRagChunkingService chunkingService) {
        this.properties = properties;
        this.chunkingService = chunkingService;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.continueChat(com.example.lms.dto.ChatRequestDto,..))")
    public Object captureConversationTurn(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        if (!properties.isEnabled()
                || !properties.getIndexing().isEnabled()
                || !properties.getIndexing().isCaptureChatWorkflow()) {
            return result;
        }
        ChatRequestDto request = firstRequest(pjp.getArgs());
        if (request == null || !(result instanceof ChatResult chatResult)) {
            return result;
        }
        String sessionId = request.getSessionId() == null ? "__TRANSIENT__" : String.valueOf(request.getSessionId());
        String userText = request.getMessage();
        String assistantText = chatResult.content();
        CompletableFuture.runAsync(() -> capture(sessionId, userText, assistantText));
        return result;
    }

    void capture(String sessionId, String userText, String assistantText) {
        try {
            chunkingService.ingestConversationTurn(sessionId, userText, assistantText);
        } catch (Exception ex) {
            String failureClass = ex == null ? "unknown" : ex.getClass().getSimpleName();
            TraceStore.put("retrieval.kg.brainState.capture.failed", true);
            TraceStore.put("retrieval.kg.brainState.capture.failureClass", failureClass);
            TraceStore.put("retrieval.kg.brainState.capture.fallback", "skip_chat_capture");
            log.debug("[AWX][brain-state][capture] skipped failureClass={} sessionHash={}",
                    failureClass, BrainStateText.hash12(sessionId));
        }
    }

    private static ChatRequestDto firstRequest(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof ChatRequestDto request)) {
            return null;
        }
        return request;
    }
}
