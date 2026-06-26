package com.example.lms.ensemble;

import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import dev.langchain4j.exception.LangChain4jException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnsembleFinalAnswerService {

    private final DiverseSamplingOrchestrator samplingOrchestrator;
    private final EnsembleJudgeService judgeService;

    @Value("${ensemble.sampling.enabled:false}")
    private boolean ensembleEnabled;

    public Optional<String> tryGenerate(PromptContext ctx, Long sessionId) {
        if (!ensembleEnabled) {
            TraceStore.put("ensemble.sampling.skipped", "disabled");
            TraceStore.put("ensemble.bypass.reason", "disabled");
            return Optional.empty();
        }
        String rid = sessionId == null ? "session-none" : "session-" + sessionId;
        try {
            List<SampledCandidate> candidates = samplingOrchestrator.sample(ctx, rid);
            if (candidates == null || candidates.isEmpty()) {
                TraceStore.put("ensemble.judge.skipped", "no_candidates");
                TraceStore.put("ensemble.bypass.reason", "no_candidates");
                return Optional.empty();
            }
            String result = judgeService.judge(candidates, ctx, rid);
            if (result == null || result.isBlank()) {
                TraceStore.put("ensemble.bypass.reason", "blank_result");
                return Optional.empty();
            }
            TraceStore.put("ensemble.used", true);
            TraceStore.put("ensemble.resultLen", result.length());
            return Optional.of(result);
        } catch (LangChain4jException | IllegalArgumentException | IllegalStateException e) {
            TraceStore.put("ensemble.bypass.reason", "ensemble_final_answer_failed");
            log.warn("[ensemble] bypass to single-path type={}", "ensemble_final_answer_failed");
            return Optional.empty();
        }
    }
}
