package com.example.lms.service.rag.graph;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrainStateChatWorkflowAspectTest {

    @Test
    void proceedsOnceAndCapturesReturnedAnswer() throws Throwable {
        BrainStateProperties props = new BrainStateProperties();
        GraphRagChunkingService service = mock(GraphRagChunkingService.class);
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatRequestDto req = ChatRequestDto.builder().sessionId(7L).message("hello").build();
        ChatResult result = ChatResult.of("answer", "model", true);
        when(pjp.getArgs()).thenReturn(new Object[]{req, null});
        when(pjp.proceed()).thenReturn(result);

        Object out = aspect.captureConversationTurn(pjp);

        assertSame(result, out);
        verify(pjp, times(1)).proceed();
        verify(service, timeout(1000)).ingestConversationTurn("7", "hello", "answer");
    }

    @Test
    void skipsCaptureWhenDisabled() throws Throwable {
        BrainStateProperties props = new BrainStateProperties();
        props.setEnabled(false);
        GraphRagChunkingService service = mock(GraphRagChunkingService.class);
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{ChatRequestDto.builder().sessionId(7L).message("hello").build(), null});
        when(pjp.proceed()).thenReturn(ChatResult.of("answer", "model", true));

        aspect.captureConversationTurn(pjp);

        verify(service, never()).ingestConversationTurn(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pointcutCoversSingleArgAndProviderOverloads() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/graph/BrainStateChatWorkflowAspect.java"));

        assertTrue(source.contains("continueChat(com.example.lms.dto.ChatRequestDto,..)"));
    }

    @Test
    void captureFailureLeavesTraceBreadcrumbWithoutRawSessionOrText() {
        TraceStore.clear();
        BrainStateProperties props = new BrainStateProperties();
        GraphRagChunkingService service = mock(GraphRagChunkingService.class);
        doThrow(new IllegalStateException("ownerToken=raw-brain-capture"))
                .when(service).ingestConversationTurn(anyString(), anyString(), anyString());
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service);

        aspect.capture("session ownerToken=raw-session", "private user text", "private assistant text");

        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.brainState.capture.failed"));
        assertEquals("IllegalStateException", TraceStore.get("retrieval.kg.brainState.capture.failureClass"));
        assertEquals("skip_chat_capture", TraceStore.get("retrieval.kg.brainState.capture.fallback"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-brain-capture"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-session"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private user text"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private assistant text"));
        TraceStore.clear();
    }
}
