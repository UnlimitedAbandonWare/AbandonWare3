package ai.abandonware.nova.orch.router;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmRouterBanditTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void directPickAndOutcomePublishCihRagRouterTrace() {
        LlmRouterBandit bandit = new LlmRouterBandit(props("gemma"));

        LlmRouterBandit.Selected selected = bandit.pick("llmrouter.gemma");

        assertEquals("gemma", selected.key());
        assertEquals("gemma", TraceStore.get("cihRag.routedModel"));
        assertEquals("gemma", TraceStore.get("llm.router.selected"));
        assertEquals("direct", TraceStore.get("llm.router.mode"));
        assertEquals("", TraceStore.get("llm.router.skipReason"));
        assertEquals(-1, TraceStore.get("cihRag.ucb1Reward"));

        bandit.recordOutcome("gemma", true, 42L);
        assertEquals(1, TraceStore.get("cihRag.ucb1Reward"));
        assertEquals(1.0d, (Double) TraceStore.get("llm.router.rewardSignal"), 1.0e-9d);

        bandit.recordOutcome("gemma", false, 42L);
        assertEquals(0, TraceStore.get("cihRag.ucb1Reward"));
        assertEquals(0.0d, (Double) TraceStore.get("llm.router.rewardSignal"), 1.0e-9d);
    }

    @Test
    void autoColdStartPublishesExploreAliasTrace() {
        LlmRouterBandit bandit = new LlmRouterBandit(props("alpha", "beta"));

        LlmRouterBandit.Selected selected = bandit.pick("llmrouter.auto");

        assertNotNull(selected);
        assertEquals(selected.key(), TraceStore.get("llm.router.selected"));
        assertEquals("explore", TraceStore.get("llm.router.mode"));
        assertEquals(0.0d, (Double) TraceStore.get("llm.router.ucb1.score"), 1.0e-9d);
        assertEquals("", TraceStore.get("llm.router.skipReason"));
    }

    @Test
    void autoPickPublishesUcb1ArmScoreAndExplorationTrace() {
        LlmRouterBandit bandit = new LlmRouterBandit(props("alpha", "beta"));
        bandit.recordOutcome("alpha", true, 25L);
        bandit.recordOutcome("beta", false, 40L);
        TraceStore.clear();

        LlmRouterBandit.Selected selected = bandit.pick("llmrouter.auto");

        assertNotNull(selected);
        assertEquals(selected.key(), TraceStore.get("llm.router.arm"));
        assertEquals(selected.key(), TraceStore.get("llm.router.selected"));
        assertEquals("exploit", TraceStore.get("llm.router.mode"));
        assertEquals("ucb1", TraceStore.get("llm.router.policy"));
        assertEquals(1L, TraceStore.get("llm.router.arm.sampleCount"));
        assertNotNull(TraceStore.get("llm.router.ucbScore"));
        assertNotNull(TraceStore.get("llm.router.ucb1.score"));
        assertNotNull(TraceStore.get("llm.router.arm.explorationBonus"));
        assertEquals("", TraceStore.get("llm.router.skipReason"));
    }

    @Test
    void autoPickPublishesSkipReasonWhenAllCandidatesAreIneligible() {
        LlmRouterBandit bandit = new LlmRouterBandit(props("alpha", "beta"));

        LlmRouterBandit.Selected selected = bandit.pick("llmrouter.auto", (key, cfg) -> false);

        assertNull(selected);
        assertEquals("skipped", TraceStore.get("llm.router.mode"));
        assertEquals(0.0d, (Double) TraceStore.get("llm.router.ucb1.score"), 1.0e-9d);
        assertEquals("no_eligible_models", TraceStore.get("llm.router.skipReason"));
    }

    private static LlmRouterProperties props(String key) {
        return props(new String[]{key});
    }

    private static LlmRouterProperties props(String... keys) {
        LlmRouterProperties props = new LlmRouterProperties();
        Map<String, LlmRouterProperties.ModelConfig> models = new LinkedHashMap<>();
        for (String key : keys) {
            LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
            cfg.setEnabled(true);
            cfg.setName("model-" + key);
            cfg.setBaseUrl("http://localhost:11434/v1");
            cfg.setWeight(1.0d);
            models.put(key, cfg);
        }
        props.setEnabled(true);
        props.setModels(models);
        return props;
    }
}
