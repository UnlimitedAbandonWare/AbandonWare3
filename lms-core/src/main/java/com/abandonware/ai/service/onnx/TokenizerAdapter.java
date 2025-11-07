package com.abandonware.ai.service.onnx;

import java.util.List;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.onnx.TokenizerAdapter
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.onnx.TokenizerAdapter
role: config
*/
public interface TokenizerAdapter {
    class EncodedTriplet {
        public final long[][] ids, attn, type;
        public EncodedTriplet(long[][] ids, long[][] attn, long[][] type) { this.ids = ids; this.attn = attn; this.type = type; }
    }
    EncodedTriplet encodePairs(List<String> queries, List<String> docs);
}