package ai.abandonware.nova.orch.router;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.LlmRouterProperties.ModelConfig;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.MlaBreadcrumb;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal UCB1-style chooser + cooldown health gate for llmrouter models.
 *
 * <p>Behavior:
 * <ul>
 *   <li>{@code llmrouter.<key>} -> direct mapping</li>
 *   <li>{@code llmrouter.auto} / {@code llmrouter} -> bandit pick across all models</li>
 *   <li>Failures push an arm into cooldown for {@code llmrouter.cooldown-ms}</li>
 * </ul>
 */
public class LlmRouterBandit {

    private static final Logger log = LoggerFactory.getLogger(LlmRouterBandit.class);

    public record Selected(String key, ModelConfig cfg) {
    }

    @FunctionalInterface
    public interface RouteEligibilityFilter {
        boolean eligible(String key, ModelConfig cfg);

        static RouteEligibilityFilter always() {
            return (key, cfg) -> true;
        }
    }

    private static final class Arm {
        final AtomicLong pulls = new AtomicLong(0L);
        final AtomicLong successes = new AtomicLong(0L);
        final AtomicLong lastFailAt = new AtomicLong(0L);

        boolean inCooldown(long nowMs, long cooldownMs) {
            if (cooldownMs <= 0L) {
                return false;
            }
            long lf = lastFailAt.get();
            return lf > 0L && (nowMs - lf) < cooldownMs;
        }
    }

    private final LlmRouterProperties props;
    private final ConcurrentHashMap<String, Arm> arms = new ConcurrentHashMap<>();

    public LlmRouterBandit(LlmRouterProperties props) {
        this.props = props;
    }

    /**
     * Picks an endpoint/model config for a requested logical model id.
     */
    public Selected pick(String requestedModelId) {
        return pick(requestedModelId, RouteEligibilityFilter.always());
    }

    /**
     * Picks an endpoint/model config using an optional runtime eligibility filter.
     * Direct llmrouter.<key> requests keep fail-closed semantics and are not filtered
     * out before validation.
     */
    public Selected pick(String requestedModelId, RouteEligibilityFilter eligibilityFilter) {
        if (props == null || !props.isEnabled()) {
            traceSkip("disabled");
            return null;
        }
        Map<String, ModelConfig> models = props.getModels();
        if (models == null || models.isEmpty()) {
            traceSkip("no_models");
            return null;
        }

        String directKey = extractKey(requestedModelId);
        if (directKey != null) {
            ModelConfig cfg = models.get(directKey);
            if (cfg == null) {
                traceSkip("direct_model_missing");
                return null;
            }
            return selected(directKey, cfg, "direct", 0.0d, "");
        }

        if (!isAuto(requestedModelId)) {
            traceSkip("not_router_model");
            return null;
        }

        return pickAuto(models, eligibilityFilter == null ? RouteEligibilityFilter.always() : eligibilityFilter);
    }

    /** Records success/failure for cooldown and bandit scoring. */
    public void recordOutcome(String key, boolean success, long latencyMs) {
        recordOutcome(key, success, latencyMs, success ? LlmFailureClass.NONE : LlmFailureClass.UNKNOWN);
    }

    /** Records success/failure with failure-class aware cooldown handling. */
    public void recordOutcome(String key, boolean success, long latencyMs, LlmFailureClass failureClass) {
        if (key == null || key.isBlank()) {
            return;
        }
        Arm arm = arms.computeIfAbsent(key, k -> new Arm());
        arm.pulls.incrementAndGet();
        if (success) {
            arm.successes.incrementAndGet();
        } else if (failureClass != LlmFailureClass.CANCELLED_NEUTRAL) {
            arm.lastFailAt.set(System.currentTimeMillis());
        }
        LlmFailureClass safeFailureClass = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
        MlaBreadcrumb.appendLlmReward(
                key,
                success,
                latencyMs,
                safeFailureClass.name().toLowerCase(java.util.Locale.ROOT));
        TraceStore.put("cihRag.ucb1Reward", success ? 1 : 0);
        TraceStore.put("llm.router.rewardSignal", success ? 1.0d : 0.0d);

        if (log.isDebugEnabled()) {
            log.debug("[llmrouter] outcome key={} success={} failureClass={} latencyMs={} pulls={} wins={} lastFailAt={}"
                    , key, success, safeFailureClass, latencyMs, arm.pulls.get(), arm.successes.get(), arm.lastFailAt.get());
        }
    }

    private Selected pickAuto(Map<String, ModelConfig> models, RouteEligibilityFilter eligibilityFilter) {
        try {
            final long now = System.currentTimeMillis();
            final long cooldownMs = Math.max(0L, props.getCooldownMs());

            List<Candidate> candidates = new ArrayList<>();

            // 1) Candidate set: enabled, weight>0 and not in cooldown.
            for (Map.Entry<String, ModelConfig> e : models.entrySet()) {
                if (e == null) {
                    continue;
                }
                String key = e.getKey();
                ModelConfig cfg = e.getValue();
                if (key == null || key.isBlank() || cfg == null) {
                    continue;
                }
                if (!cfg.isEnabled() || cfg.getWeight() <= 0.0d) {
                    continue;
                }
                if (!eligibilityFilter.eligible(key, cfg)) {
                    continue;
                }

                Arm arm = arms.computeIfAbsent(key, k -> new Arm());
                if (arm.inCooldown(now, cooldownMs)) {
                    continue;
                }
                candidates.add(new Candidate(key, cfg, arm));
            }

            // 2) If all are in cooldown, ignore cooldown and use weight>0.
            if (candidates.isEmpty()) {
                for (Map.Entry<String, ModelConfig> e : models.entrySet()) {
                    if (e == null) {
                        continue;
                    }
                    String key = e.getKey();
                    ModelConfig cfg = e.getValue();
                    if (key == null || key.isBlank() || cfg == null) {
                        continue;
                    }
                    if (!cfg.isEnabled() || cfg.getWeight() <= 0.0d) {
                        continue;
                    }
                    if (!eligibilityFilter.eligible(key, cfg)) {
                        continue;
                    }

                    Arm arm = arms.computeIfAbsent(key, k -> new Arm());
                    candidates.add(new Candidate(key, cfg, arm));
                }
            }

            if (candidates.isEmpty()) {
                traceSkip("no_eligible_models");
                return null;
            }

            // 3) Exploration: any never-tried arm.
            for (Candidate c : candidates) {
                if (c.arm.pulls.get() == 0L) {
                    return selected(c.key, c.cfg, "explore", 0.0d, "");
                }
            }

            // 4) UCB1-ish score.
            long totalPulls = 0L;
            for (Candidate c : candidates) {
                totalPulls += Math.max(1L, c.arm.pulls.get());
            }
            double logTotal = Math.log(Math.max(1d, (double) totalPulls));

            Candidate best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            long bestSampleCount = 0L;
            double bestExplorationBonus = 0.0d;

            for (Candidate c : candidates) {
                long n = Math.max(1L, c.arm.pulls.get());
                double mean = c.arm.successes.get() / (double) n;
                double bonus = Math.sqrt(2.0d * logTotal / (double) n);
                double prior = clamp01(c.cfg.getWeight()) * 0.01d; // tiny tie-breaker
                double score = mean + bonus + prior;

                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                    bestSampleCount = n;
                    bestExplorationBonus = bonus;
                }
            }

            if (best == null) {
                return pickWeightedRandom(candidates);
            }

            TraceStore.put("llm.router.arm", SafeRedactor.traceLabelOrFallback(best.key, "unknown"));
            TraceStore.put("llm.router.ucbScore", bestScore);
            TraceStore.put("llm.router.ucb1.score", bestScore);
            TraceStore.put("llm.router.arm.sampleCount", bestSampleCount);
            TraceStore.put("llm.router.arm.explorationBonus", bestExplorationBonus);
            TraceStore.put("llm.router.policy", "ucb1");
            return selected(best.key, best.cfg, "exploit", bestScore, "");
        } catch (Exception ex) {
            traceSkip("pick_auto_error");
            log.debug("[llmrouter] pickAuto fail-soft: errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
            return null;
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }

    private Selected pickWeightedRandom(List<Candidate> candidates) {
        double total = 0d;
        for (Candidate c : candidates) {
            total += Math.max(0d, c.cfg.getWeight());
        }

        if (total <= 0d) {
            Candidate c = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            return selected(c.key, c.cfg, "explore", 0.0d, "");
        }

        double r = ThreadLocalRandom.current().nextDouble(total);
        double acc = 0d;
        for (Candidate c : candidates) {
            acc += Math.max(0d, c.cfg.getWeight());
            if (acc >= r) {
                return selected(c.key, c.cfg, "explore", 0.0d, "");
            }
        }

        Candidate last = candidates.get(candidates.size() - 1);
        return selected(last.key, last.cfg, "explore", 0.0d, "");
    }

    private static Selected selected(String key, ModelConfig cfg) {
        return selected(key, cfg, "direct", 0.0d, "");
    }

    private static Selected selected(String key, ModelConfig cfg, String mode, double ucbScore, String skipReason) {
        String safeKey = SafeRedactor.traceLabelOrFallback(key, "unknown");
        TraceStore.put("cihRag.routedModel", SafeRedactor.traceLabelOrFallback(key, "unknown"));
        TraceStore.put("cihRag.ucb1Reward", -1);
        TraceStore.put("llm.router.selected", safeKey);
        TraceStore.put("llm.router.mode", SafeRedactor.traceLabelOrFallback(mode, "unknown"));
        TraceStore.put("llm.router.ucb1.score", Double.isFinite(ucbScore) ? ucbScore : 0.0d);
        TraceStore.put("llm.router.skipReason", SafeRedactor.traceLabelOrFallback(skipReason, ""));
        return new Selected(key, cfg);
    }

    private static void traceSkip(String reason) {
        TraceStore.put("llm.router.skipReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        TraceStore.put("llm.router.mode", "skipped");
        TraceStore.put("llm.router.ucb1.score", 0.0d);
    }

    /**
     * @return key for direct routing, or null for non-llmrouter model ids / auto.
     */
    public static String extractKey(String modelId) {
        if (modelId == null) {
            return null;
        }
        String s = modelId.trim();
        if (!s.toLowerCase().startsWith("llmrouter.")) {
            return null;
        }
        String rest = s.substring("llmrouter.".length());
        int colon = rest.indexOf(':');
        if (colon > 0) {
            rest = rest.substring(0, colon);
        }
        rest = rest.trim();
        if (rest.isEmpty() || rest.equalsIgnoreCase("auto")) {
            return null;
        }
        return rest;
    }

    private static boolean isAuto(String modelId) {
        if (modelId == null) {
            return false;
        }
        String s = modelId.trim().toLowerCase();
        return Objects.equals(s, "llmrouter")
                || Objects.equals(s, "llmrouter.auto")
                || Objects.equals(s, "llmrouter.");
    }

    private static double clamp01(double v) {
        if (v < 0.0d) {
            return 0.0d;
        }
        if (v > 1.0d) {
            return 1.0d;
        }
        return v;
    }

    private record Candidate(String key, ModelConfig cfg, Arm arm) {
    }
}
