package com.example.lms.ensemble;

import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.LangChain4jException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnsembleJudgeService {

    private final DynamicChatModelFactory modelFactory;
    private final PromptBuilder promptBuilder;

    @Value("${ensemble.sampling.judge-model:${llm.chat-model:gemma4:26b}}")
    private String judgeModel = "gemma4:26b";

    @Value("${ensemble.judge.temperature:0.3}")
    private double judgeTemperature = 0.3d;

    @Value("${ensemble.judge.top-p:0.5}")
    private double judgeTopP = 0.5d;

    @Value("${ensemble.judge.max-tokens:2000}")
    private int judgeMaxTokens = 2000;

    public String judge(List<SampledCandidate> candidates, PromptContext ctx, String rid) {
        if (candidates == null || candidates.isEmpty()) {
            TraceStore.put("ensemble.judge.skipped", "no_candidates");
            return null;
        }

        try {
            PromptContext judgeContext = (ctx == null ? PromptContext.builder() : ctx.toBuilder())
                    .ensembleCandidates(candidates)
                    .ensembleJudgeMode(true)
                    .build();
            String judgePrompt = promptBuilder.build(judgeContext);
            String result = modelFactory
                    .lc(judgeModel, judgeTemperature, judgeTopP, null, null, judgeMaxTokens)
                    .chat(List.of(UserMessage.from(judgePrompt)))
                    .aiMessage()
                    .text();

            TraceStore.put("ensemble.judge.modelHash", SafeRedactor.hashValue(judgeModel));
            TraceStore.put("ensemble.judge.modelLength", judgeModel == null ? 0 : judgeModel.length());
            TraceStore.put("ensemble.judge.candidateCount", candidates.size());
            TraceStore.put("ensemble.judge.resultLen", result == null ? 0 : result.length());
            if (result == null || result.isBlank()) {
                TraceStore.put("ensemble.judge.empty", "blank_judge_output");
                return pickBestCandidate(candidates);
            }
            return result;
        } catch (LangChain4jException | IllegalArgumentException | IllegalStateException e) {
            TraceStore.put("ensemble.judge.fail", "ensemble_judge_failed");
            log.warn("[ensemble][judge] fail rid={} type={}",
                    SafeRedactor.hashValue(rid),
                    "ensemble_judge_failed");
            return pickBestCandidate(candidates);
        }
    }

    private static String pickBestCandidate(List<SampledCandidate> candidates) {
        return candidates.stream()
                .filter(candidate -> candidate != null
                        && candidate.gateResult() != FinalSigmoidGate.GateResult.BLOCK)
                .max(Comparator.comparingDouble(SampledCandidate::citationScore))
                .map(SampledCandidate::text)
                .map(SafeRedactor::redact)
                .orElse(null);
    }
}
