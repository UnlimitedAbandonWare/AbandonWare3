package com.abandonware.ai.agent.rerank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.Semaphore;

@Component
@ConditionalOnProperty(name = "onnx.enabled", havingValue = "true", matchIfMissing = false)
public class OnnxCrossEncoderReranker {
    private final Semaphore limiter;
    public OnnxCrossEncoderReranker(Semaphore limiter) {
        this.limiter = limiter;
    }
    public List<BiEncoderReranker.DocScore> rerank(List<BiEncoderReranker.DocScore> topK) {
        try {
            limiter.acquire();
            // placeholder scoring refinement
            topK.sort(Comparator.comparingDouble((BiEncoderReranker.DocScore ds) -> ds.score).reversed());
            return topK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return topK;
        } finally {
            limiter.release();
        }
    }
}
