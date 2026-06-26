package com.example.lms.ensemble;

import com.example.lms.guard.FinalSigmoidGate;

/**
 * Immutable metadata for one ensemble sampling node output.
 */
public record SampledCandidate(
        String nodeId,
        String text,
        double temperature,
        double topP,
        double citationScore,
        double riskScore,
        FinalSigmoidGate.GateResult gateResult) {
}
