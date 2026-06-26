package com.example.lms.prompt;

import com.example.lms.ensemble.SampledCandidate;
import com.example.lms.guard.FinalSigmoidGate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardPromptBuilderEnsembleJudgeModeTest {

    private final StandardPromptBuilder builder = new StandardPromptBuilder();

    @Test
    void judgeModeRendersCandidatesBeforeUserQuestion() {
        PromptContext ctx = PromptContext.builder()
                .userQuery("final answer?")
                .ensembleJudgeMode(true)
                .ensembleCandidates(List.of(new SampledCandidate(
                        "explore",
                        "candidate answer with cited evidence",
                        1.4d,
                        0.9d,
                        0.85d,
                        0.10d,
                        FinalSigmoidGate.GateResult.PASS)))
                .build();

        String prompt = builder.build(ctx);

        assertTrue(prompt.contains("### ENSEMBLE CANDIDATES"), prompt);
        assertTrue(prompt.contains("node=explore"), prompt);
        assertTrue(prompt.contains("citation=0.85"), prompt);
        assertTrue(prompt.contains("candidate answer with cited evidence"), prompt);
        assertTrue(prompt.indexOf("### ENSEMBLE CANDIDATES") < prompt.indexOf("### USER QUESTION"), prompt);
    }

    @Test
    void judgeModeRedactsSecretLikeCandidateTextBeforePrompt() {
        String rawKey = "sk-" + "1234567890abcdef1234";
        PromptContext ctx = PromptContext.builder()
                .userQuery("final answer?")
                .ensembleJudgeMode(true)
                .ensembleCandidates(List.of(new SampledCandidate(
                        "explore",
                        "candidate saw " + rawKey,
                        1.4d,
                        0.9d,
                        0.85d,
                        0.10d,
                        FinalSigmoidGate.GateResult.PASS)))
                .build();

        String prompt = builder.build(ctx);

        assertFalse(prompt.contains(rawKey), prompt);
        assertFalse(prompt.contains("1234567890abcdef1234"), prompt);
        assertTrue(prompt.contains("candidate saw "), prompt);
        assertTrue(prompt.contains("***"), prompt);
    }
}
