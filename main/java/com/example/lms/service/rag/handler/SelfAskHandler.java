package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class SelfAskHandler extends AbstractRetrievalHandler {

    private static final Logger log = LoggerFactory.getLogger(SelfAskHandler.class);

    private final SelfAskWebSearchRetriever retriever;
    private final OrchestrationGate gate;

    public SelfAskHandler(SelfAskWebSearchRetriever retriever, OrchestrationGate gate) {
        this.retriever = retriever;
        this.gate = gate;
    }

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            if (gate != null && !gate.allowSelfAsk(q)) {
                log.debug("[SelfAsk] skipped by orchestration gate");
                return true;
            }

            acc.addAll(retriever.retrieve(q));
            // 항상 다음 핸들러도 시도한다.
            return true;
        } catch (Exception e) {
            log.warn("[AWX][rag][handler] selfAsk failed failureReason={} errorType={} queryHash12={} queryLength={}",
                    "selfask-handler-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hash12(q == null ? null : q.text()),
                    q == null || q.text() == null ? 0 : q.text().length());
            return true;
        }
    }

    // [HARDENING] ensure SID metadata is present on every query
    @SuppressWarnings("unused")
    private dev.langchain4j.rag.query.Query ensureSidMetadata(
            dev.langchain4j.rag.query.Query original,
            String sessionKey
    ) {
        java.util.Map<String, Object> md = new java.util.LinkedHashMap<>(QueryUtils.metadata(original));
        md.put(com.example.lms.service.rag.LangChainRAGService.META_SID, sessionKey);

        // LangChain4j 1.0.x 에서는 (text, metadata)를 받는 public 생성자를 제공
        return QueryUtils.buildQuery(original.text(), md);
    }
}
