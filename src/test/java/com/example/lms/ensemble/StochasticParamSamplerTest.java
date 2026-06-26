package com.example.lms.ensemble;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StochasticParamSamplerTest {

    private static final Set<String> ALLOWED_PARAMS = Set.of(
            "0.20/0.30",
            "0.55/0.60",
            "1.10/0.85",
            "1.40/0.95");

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void drawReturnsOnlyAllowedPillMappingsAndWritesTrace() {
        StochasticParamSampler sampler = new StochasticParamSampler();

        for (int i = 0; i < 80; i++) {
            String traceId = "rid-" + i;

            StochasticParamSampler.DrawResult result = sampler.draw(traceId);

            assertEquals(3, result.caffeine() + result.theanine());
            String key = String.format(java.util.Locale.ROOT, "%.2f/%.2f",
                    result.temperature(), result.topP());
            assertTrue(ALLOWED_PARAMS.contains(key), "unexpected params: " + key);
            Object trace = TraceStore.get("ensemble.stoch.draw." + traceId);
            assertTrue(String.valueOf(trace).contains("caffeine=" + result.caffeine()));
            assertTrue(String.valueOf(trace).contains("theanine=" + result.theanine()));
        }
    }
}
