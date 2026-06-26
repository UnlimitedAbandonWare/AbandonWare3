package com.example.lms.ensemble;

import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiverseSamplingOrchestratorTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void disabledSamplingReturnsEmptyAndWritesSkipReason() {
        RecordingFactory factory = new RecordingFactory("unused");
        DiverseSamplingOrchestrator orchestrator = orchestrator(factory);

        List<SampledCandidate> candidates = orchestrator.sample(PromptContext.builder().userQuery("q").build(), "rid");

        assertTrue(candidates.isEmpty());
        assertEquals("disabled", TraceStore.get("ensemble.sampling.skipped"));
        assertTrue(factory.requests.isEmpty());
    }

    @Test
    void enabledSamplingRunsThreeProfilesAndRecordsCounts() {
        RecordingFactory factory = new RecordingFactory("grounded answer with enough detail");
        DiverseSamplingOrchestrator orchestrator = orchestrator(factory);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "samplingTimeoutSeconds", 2);
        PromptContext ctx = PromptContext.builder()
                .userQuery("q")
                .sourceUrls(List.of("https://official.example/a", "https://docs.example/b"))
                .officialSources(List.of("https://official.example/a"))
                .build();

        List<SampledCandidate> candidates = orchestrator.sample(ctx, "rid");

        assertEquals(3, factory.requests.size());
        assertFalse(candidates.isEmpty());
        assertEquals(candidates.size(), TraceStore.get("ensemble.candidates.count"));
        assertTrue(factory.requests.contains("gemma4:26b/1.40/0.90"));
        assertTrue(factory.requests.contains("gemma4:26b/0.40/0.40"));
        assertTrue(factory.requests.stream().anyMatch(value ->
                value.endsWith("/0.20/0.30")
                        || value.endsWith("/0.55/0.60")
                        || value.endsWith("/1.10/0.85")
                        || value.endsWith("/1.40/0.95")));
    }

    @Test
    void nodeExecutionFailureRecordsNodeFailTraceAndReturnsNoCandidates() {
        DiverseSamplingOrchestrator orchestrator = orchestrator(new ThrowingFactory());
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "samplingTimeoutSeconds", 2);

        List<SampledCandidate> candidates = orchestrator.sample(PromptContext.builder().userQuery("q").build(), "rid");

        assertTrue(candidates.isEmpty());
        assertEquals("ensemble_node_failed", TraceStore.get("ensemble.node.explore.fail"));
        assertEquals("ensemble_node_failed", TraceStore.get("ensemble.node.deterministic.fail"));
        assertEquals("ensemble_node_failed", TraceStore.get("ensemble.node.stochastic_buffer.fail"));
        assertEquals("ensemble_node_failed", TraceStore.get("ensemble.error.rid"));
        assertEquals(0, TraceStore.get("ensemble.candidates.count"));
    }

    @Test
    void samplingPromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String samplingPrompt = promptBuilder.build(ctx);"));
        assertTrue(source.contains("UserMessage.from(samplingPrompt)"));
    }

    private static DiverseSamplingOrchestrator orchestrator(DynamicChatModelFactory factory) {
        return new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                new PromptBuilder() {
                    @Override
                    public String build(List<PromptContext> contexts, String question) {
                        return "prompt " + question;
                    }
                });
    }

    private static final class RecordingFactory extends DynamicChatModelFactory {
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final String responseText;

        private RecordingFactory(String responseText) {
            super(null, null);
            this.responseText = responseText;
        }

        @Override
        public ChatModel lc(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens) {
            requests.add(String.format(Locale.ROOT, "%s/%.2f/%.2f", modelName, temperature, topP));
            return new StubModel(responseText);
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

    private static final class ThrowingFactory extends DynamicChatModelFactory {
        private ThrowingFactory() {
            super(null, null);
        }

        @Override
        public ChatModel lc(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens) {
            return new ThrowingModel();
        }
    }

    private record ThrowingModel() implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            throw new IllegalStateException("model unavailable");
        }
    }
}
