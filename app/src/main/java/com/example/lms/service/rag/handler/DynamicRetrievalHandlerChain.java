package com.example.lms.service.rag.handler;

import java.util.*;

/**
 * Minimal, compile-safe dynamic ordering chain.
 */
public class DynamicRetrievalHandlerChain {

    private final List<String> stages = new ArrayList<>();

    public DynamicRetrievalHandlerChain() {
        // Revised default ordering aligning with KG/DPP/2‑pass reranking.
        // When KG is preferred hints may move this earlier, but by default
        // web retrieval precedes vector retrieval. After primary retrieval
        // steps RRF fusion and rerankers are applied in sequence:
        // RRF fusion -> DPP diversity -> BiEncoder -> CrossEncoder -> FinalSigmoid -> CitationGate.
        stages.add("WEB");
        stages.add("VECTOR");
        stages.add("RRF");
        stages.add("DPP");
        stages.add("BI");
        stages.add("CROSS");
        stages.add("SIGMOID");
        stages.add("CITATION");
        stages.add("KG");
    }

    /** Returns an immutable view of stage ordering. */
    public List<String> order() {
        return Collections.unmodifiableList(stages);
    }
}