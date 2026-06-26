package com.example.lms.ensemble;

import com.example.lms.guard.CitationGate;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiverseSamplingOrchestrator {

    private final DynamicChatModelFactory modelFactory;
    private final StochasticParamSampler stochasticSampler;
    private final FinalSigmoidGate sigmoidGate;
    private final PromptBuilder promptBuilder;
    private final CitationGate citationGate = new CitationGate();

    @Value("${ensemble.sampling.model:${llm.fast.model:gemma4:26b}}")
    private String samplingModel = "gemma4:26b";

    @Value("${ensemble.sampling.timeout-seconds:30}")
    private int samplingTimeoutSeconds = 30;

    @Value("${ensemble.sampling.enabled:false}")
    private boolean ensembleEnabled;

    public List<SampledCandidate> sample(PromptContext ctx, String rid) {
        if (!ensembleEnabled) {
            TraceStore.put("ensemble.sampling.skipped", "disabled");
            return List.of();
        }

        String safeRid = rid == null || rid.isBlank() ? "unknown" : rid.trim();
        StochasticParamSampler.DrawResult draw = stochasticSampler.draw(safeRid);
        List<NodeSpec> specs = List.of(
                new NodeSpec("explore", 1.4d, 0.9d),
                new NodeSpec("deterministic", 0.4d, 0.4d),
                new NodeSpec("stochastic_buffer", draw.temperature(), draw.topP()));

        ExecutorService exec = Executors.newFixedThreadPool(specs.size(), daemonThreadFactory());
        try {
            List<NodeFuture> futures = new ArrayList<>();
            for (NodeSpec spec : specs) {
                futures.add(new NodeFuture(spec, exec.submit(() -> runNode(spec, ctx, safeRid))));
            }

            List<SampledCandidate> results = new ArrayList<>();
            long timeout = Math.max(1, samplingTimeoutSeconds);
            for (NodeFuture nodeFuture : futures) {
                try {
                    Future<SampledCandidate> future = nodeFuture.future();
                    SampledCandidate candidate = future.get(timeout, TimeUnit.SECONDS);
                    if (candidate != null && candidate.gateResult() != FinalSigmoidGate.GateResult.BLOCK) {
                        results.add(candidate);
                    } else if (candidate != null) {
                        TraceStore.put("ensemble.node." + candidate.nodeId() + ".blocked",
                                "gateResult=" + candidate.gateResult() + ",risk=" + format(candidate.riskScore()));
                    }
                } catch (TimeoutException e) {
                    TraceStore.put("ensemble.timeout." + safeRid, "node timeout after " + timeout + "s");
                    nodeFuture.future().cancel(false);
                    log.warn("[ensemble] node timeout rid={}", safeRid);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    TraceStore.put("ensemble.error." + safeRid, "interrupted");
                    nodeFuture.future().cancel(false);
                    log.warn("[ensemble] node interrupted rid={}", safeRid);
                } catch (ExecutionException e) {
                    String type = "ensemble_node_failed";
                    TraceStore.put("ensemble.node." + nodeFuture.spec().id() + ".fail", type);
                    TraceStore.put("ensemble.error." + safeRid, type);
                    log.warn("[ensemble] node error rid={} type={}", safeRid, type);
                }
            }

            TraceStore.put("ensemble.candidates.count", results.size());
            TraceStore.put("ensemble.stoch.caffeine", draw.caffeine());
            TraceStore.put("ensemble.stoch.theanine", draw.theanine());
            return List.copyOf(results);
        } finally {
            exec.shutdown();
        }
    }

    private SampledCandidate runNode(NodeSpec spec, PromptContext ctx, String rid) {
        String samplingPrompt = promptBuilder.build(ctx);
        ChatModel model = modelFactory.lc(samplingModel, spec.temperature(), spec.topP(), null, null, 1200);
        String text = model.chat(List.of(UserMessage.from(samplingPrompt))).aiMessage().text();
        if (text == null || text.isBlank()) {
            TraceStore.put("ensemble.node." + spec.id() + ".empty", "blank_output");
            return null;
        }

        List<String> sources = ctx == null ? List.of() : ctx.sourceUrls();
        List<String> official = ctx == null ? List.of() : ctx.officialSources();
        double citationScore = sources.isEmpty() ? 0.0d : (citationGate.check(sources, official) ? 0.85d : 0.4d);
        double policyRisk = detectPolicyRisk(text);
        double hallucinationProxy = text.length() < 20 ? 0.8d : 0.2d;
        double compositeScore = sigmoidGate.score(hallucinationProxy, policyRisk, 1.0d - citationScore);
        FinalSigmoidGate.GateResult gateResult =
                sigmoidGate.check(compositeScore, policyRisk, citationScore > 0.7d);

        TraceStore.put("ensemble.node." + spec.id() + ".score",
                "citation=" + format(citationScore)
                        + ",risk=" + format(policyRisk)
                        + ",gate=" + gateResult);
        return new SampledCandidate(spec.id(), text, spec.temperature(), spec.topP(),
                citationScore, policyRisk, gateResult);
    }

    private static double detectPolicyRisk(String text) {
        if (text == null || text.isBlank()) {
            return 1.0d;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        long hits = List.of("prescription", "diagnosis", "lawsuit", "weapon", "bomb")
                .stream()
                .filter(lower::contains)
                .count();
        return Math.min(1.0d, hits * 0.3d);
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "ensemble-sampling-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record NodeSpec(String id, double temperature, double topP) {
    }

    private record NodeFuture(NodeSpec spec, Future<SampledCandidate> future) {
    }
}
