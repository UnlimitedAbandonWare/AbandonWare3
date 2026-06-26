package com.example.lms.ensemble;

import com.example.lms.search.TraceStore;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Draws three pills from a 15 caffeine / 15 theanine pool and maps the draw to
 * sampling parameters for the stochastic ensemble node.
 */
@Component
public class StochasticParamSampler {

    private static final int POOL_CAFFEINE = 15;
    private static final int POOL_THEANINE = 15;
    private static final int DRAW_COUNT = 3;

    public record DrawResult(double temperature, double topP, int caffeine, int theanine) {
    }

    public DrawResult draw(String traceId) {
        int[] pool = new int[POOL_CAFFEINE + POOL_THEANINE];
        for (int i = 0; i < POOL_CAFFEINE; i++) {
            pool[i] = 1;
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = pool.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = pool[i];
            pool[i] = pool[j];
            pool[j] = tmp;
        }

        int caffeine = 0;
        for (int i = 0; i < DRAW_COUNT; i++) {
            caffeine += pool[i];
        }
        int theanine = DRAW_COUNT - caffeine;

        DrawResult result = switch (caffeine) {
            case 3 -> new DrawResult(0.2d, 0.3d, caffeine, theanine);
            case 2 -> new DrawResult(0.55d, 0.6d, caffeine, theanine);
            case 1 -> new DrawResult(1.1d, 0.85d, caffeine, theanine);
            default -> new DrawResult(1.4d, 0.95d, caffeine, theanine);
        };

        String safeTraceId = traceId == null || traceId.isBlank() ? "unknown" : traceId.trim();
        TraceStore.put("ensemble.stoch.draw." + safeTraceId, String.format(Locale.ROOT,
                "caffeine=%d,theanine=%d,temp=%.2f,top_p=%.2f",
                result.caffeine(), result.theanine(), result.temperature(), result.topP()));
        return result;
    }
}
