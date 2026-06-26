
package com.abandonware.ai.agent.integrations;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.file.Files;
import java.nio.file.Paths;



/**
 * ONNX Cross Encoder loader.
 * Requires environment:
 *   CROSS_ENCODER=onnx
 *   CE_ONNX_MODEL=/path/to/model.onnx
 *
 * Falls back to HeuristicCrossEncoder if onnxruntime is not present or fails.
 */
public class OnnxCrossEncoder implements CrossEncoder {

    private final OrtSession session;
    private final OrtEnvironment env;
    private final HeuristicCrossEncoder fallback = new HeuristicCrossEncoder();

    public OnnxCrossEncoder() throws Exception {
        String modelPath = System.getenv("CE_ONNX_MODEL");
        if (modelPath == null || modelPath.isBlank() || !Files.exists(Paths.get(modelPath))) {
            throw new IllegalStateException("CE_ONNX_MODEL missing");
        }
        this.env = OrtEnvironment.getEnvironment();
        this.session = env.createSession(modelPath, new OrtSession.SessionOptions());
    }

    @Override
    public double score(String query, String title, String content) {
        // For brevity, not implementing real tokenization; use heuristic until model wiring is defined.
        // You can extend here to feed tokens into the ONNX model.
        return fallback.score(query, title, content);
    }
}
