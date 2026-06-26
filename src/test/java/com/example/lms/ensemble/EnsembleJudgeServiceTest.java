package com.example.lms.ensemble;

import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnsembleJudgeServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void emptyCandidatesSkipJudge() {
        EnsembleJudgeService service = new EnsembleJudgeService(new StubFactory("unused"), builder());

        assertNull(service.judge(List.of(), PromptContext.builder().userQuery("q").build(), "rid"));

        assertEquals("no_candidates", TraceStore.get("ensemble.judge.skipped"));
    }

    @Test
    void blankJudgeOutputFallsBackToBestCitationCandidate() {
        AtomicBoolean sawJudgeMode = new AtomicBoolean(false);
        EnsembleJudgeService service = new EnsembleJudgeService(new StubFactory(""), (contexts, question) -> {
            PromptContext ctx = contexts.get(0);
            sawJudgeMode.set(ctx.ensembleJudgeMode() && ctx.ensembleCandidates().size() == 2);
            return "judge prompt";
        });
        SampledCandidate weak = candidate("weak", "weak text", 0.20d);
        SampledCandidate strong = candidate("strong", "strong text", 0.90d);

        String result = service.judge(List.of(weak, strong), PromptContext.builder().userQuery("q").build(), "rid");

        assertEquals("strong text", result);
        assertTrue(sawJudgeMode.get());
        assertEquals(SafeRedactor.hashValue("gemma4:26b"), TraceStore.get("ensemble.judge.modelHash"));
        assertEquals("gemma4:26b".length(), TraceStore.get("ensemble.judge.modelLength"));
        assertNull(TraceStore.get("ensemble.judge.model"));
        assertEquals(2, TraceStore.get("ensemble.judge.candidateCount"));
        assertEquals(0, TraceStore.get("ensemble.judge.resultLen"));
    }

    @Test
    void blankJudgeFallbackRedactsSecretLikeCandidateText() {
        EnsembleJudgeService service = new EnsembleJudgeService(new StubFactory(""), builder());
        String rawKey = "sk-" + "1234567890abcdef1234";
        SampledCandidate candidate = candidate("strong", "fallback saw " + rawKey, 0.90d);

        String result = service.judge(List.of(candidate), PromptContext.builder().userQuery("q").build(), "rid");

        assertFalse(result.contains(rawKey), result);
        assertFalse(result.contains("1234567890abcdef1234"), result);
        assertTrue(result.contains("fallback saw "), result);
        assertTrue(result.contains("***"), result);
    }

    @Test
    void judgeFailureRecordsStableTraceLabel() {
        EnsembleJudgeService service = new EnsembleJudgeService(new ThrowingFactory(), builder());
        SampledCandidate candidate = candidate("strong", "fallback text", 0.90d);

        assertEquals("fallback text",
                service.judge(List.of(candidate), PromptContext.builder().userQuery("q").build(), "rid"));

        assertEquals("ensemble_judge_failed", TraceStore.get("ensemble.judge.fail"));
        assertFalse(String.valueOf(TraceStore.get("ensemble.judge.fail")).contains("IllegalStateException"));
    }

    @Test
    void judgePromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/ensemble/EnsembleJudgeService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String judgePrompt = promptBuilder.build(judgeContext);"));
        assertTrue(source.contains("UserMessage.from(judgePrompt)"));
    }

    private static PromptBuilder builder() {
        return (contexts, question) -> "prompt";
    }

    private static SampledCandidate candidate(String nodeId, String text, double citationScore) {
        return new SampledCandidate(
                nodeId,
                text,
                0.4d,
                0.4d,
                citationScore,
                0.1d,
                FinalSigmoidGate.GateResult.PASS);
    }

    private static final class StubFactory extends DynamicChatModelFactory {
        private final String responseText;

        private StubFactory(String responseText) {
            super(null, null);
            this.responseText = responseText;
        }

        @Override
        public ChatModel lc(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens) {
            return new StubModel(responseText);
        }
    }

    private static final class ThrowingFactory extends DynamicChatModelFactory {
        private ThrowingFactory() {
            super(null, null);
        }

        @Override
        public ChatModel lc(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens) {
            throw new IllegalStateException("judge model down");
        }
    }

    private record StubModel(String text) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }
}
