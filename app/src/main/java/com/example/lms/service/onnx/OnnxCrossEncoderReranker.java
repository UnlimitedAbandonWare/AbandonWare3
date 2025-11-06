
package com.example.lms.service.onnx;

import com.abandonware.ai.addons.model.ContextSlice;
import com.example.lms.trace.TraceContext;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.Semaphore;
import java.util.*;
/**
 * Lightweight concurrency-gated reranker stub. If the gate is saturated or budget elapsed,
 * we fall back to the input ordering (bi-encoder scores).
 */
@Component
public class OnnxCrossEncoderReranker {

    private final Semaphore gate;
    @Autowired(required=false) private TraceContext trace;

    public OnnxCrossEncoderReranker(
            @Value("${onnx.maxInFlight:4}") int maxInFlight) {
        this.gate = new Semaphore(Math.max(1, maxInFlight));
    }

    public List<ContextSlice> rerankTopK(List<ContextSlice> in, int topK){
        if (in == null) return List.of();
        if (topK <=0) topK = Math.min(10, in.size());
        long remain = (trace==null? Long.MAX_VALUE : trace.remainingMillis());
        if (remain <= 0) return takeTop(in, topK); // budget exhausted

        boolean acquired = gate.tryAcquire();
        if (!acquired) return takeTop(in, topK);
        try {
            // Fake rerank: stable sort by descending score and id as tiebreaker
            List<ContextSlice> copy = new ArrayList<>(in);
            copy.sort(Comparator.<ContextSlice>comparingDouble(s -> -s.score)
                    .thenComparing(s -> s.id == null ? "" : s.id));
            if (copy.size() > topK) copy = copy.subList(0, topK);
            for (int i=0;i<copy.size();i++) copy.get(i).rank = i+1;
            return copy;
        } finally {
            gate.release();
        }
    }

    private List<ContextSlice> takeTop(List<ContextSlice> in, int k){
        List<ContextSlice> out = new ArrayList<>(Math.min(k, in.size()));
        for (int i=0;i<in.size() && out.size()<k;i++){
            out.add(in.get(i));
        }
        return out;
    }
}