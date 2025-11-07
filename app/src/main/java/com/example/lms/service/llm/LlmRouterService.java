package com.example.lms.service.llm;

import com.example.lms.config.LlmProperties;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple load balancer for local LLM backends.  This service selects
 * between multiple configured vLLM endpoints based on the number of
 * in-flight requests.  Should a call fail or time out the router can
 * optionally fall back to a remote OpenAI provider if configured.
 */
@Component
public class LlmRouterService {
    private final LlmProperties conf;
    private final LocalOpenAiClient local;
    /**
     * In-flight request counters for each local backend. These counters
     * provide a simple measure of current load on each vLLM server and are
     * used when selecting a backend under the capacity_first policy.
     */
    private final AtomicInteger inflight0 = new AtomicInteger();
    private final AtomicInteger inflight1 = new AtomicInteger();

    /**
     * Health flags updated by {@link #healthPing()}. A backend is considered
     * healthy when its /models endpoint responds within a short timeout.
     * When a backend is unhealthy its inflight count is ignored and it will
     * only be selected if no other backend is available.
     */
    private volatile boolean h0 = false;
    private volatile boolean h1 = false;

    public LlmRouterService(LlmProperties conf, LocalOpenAiClient local) {
        this.conf = conf;
        this.local = local;
    }

    /**
     * Generate a streaming response for the given prompt.  The estimated
     * token count may be used to influence routing decisions but is
     * currently unused.
     *
     * @param prompt the prompt to send to the LLM
     * @param estTokens an approximate upper bound on the expected output length
     * @return a Flux of raw JSON chunks
     */
    public Flux<String> generateStream(String prompt, int estTokens) {
        // Pick a backend based on health and current load. If both
        // backends are unhealthy fall back to index 0. When a backend is
        // selected increment its inflight counter and ensure the counter
        // is decremented when the stream terminates.
        LlmProperties.Backend be = pickBackend();
        AtomicInteger counter = (be.getId() != null && be.getId().endsWith("0")) ? inflight0 : inflight1;
        counter.incrementAndGet();
        return local.stream(be.getBaseUrl(), be.getModel(), prompt)
            .timeout(Duration.ofMillis(conf.getRouter().getTimeoutMs()))
            .doFinally(s -> counter.decrementAndGet())
            .onErrorResume(ex -> fallbackStream(prompt));
    }

    private LlmProperties.Backend pickBackend() {
        List<LlmProperties.Backend> list = conf.getLocal();
        if (list == null || list.isEmpty()) {
            throw new IllegalStateException("No local LLM backends configured");
        }
        if (list.size() == 1) {
            return list.get(0);
        }
        // Determine health-aware load for each backend. Use Integer.MAX_VALUE
        // when unhealthy to deprioritise that backend. When both are
        // unhealthy simply return the first backend.
        int c0 = h0 ? inflight0.get() : Integer.MAX_VALUE;
        int c1 = h1 ? inflight1.get() : Integer.MAX_VALUE;
        if (c0 == Integer.MAX_VALUE && c1 == Integer.MAX_VALUE) {
            return list.get(0);
        }
        return (c0 <= c1) ? list.get(0) : list.get(1);
    }

    /**
     * Periodically ping each configured local backend to update health flags.
     * A backend is considered healthy when its /models endpoint returns within
     * a short timeout. This method is scheduled automatically by Spring and
     * runs at a fixed delay defined by the llm.router.health-interval-ms
     * property (defaults to 5000ms).
     */
    @Scheduled(fixedDelayString = "\${llm.router.health-interval-ms:5000}")
    void healthPing() {
        List<LlmProperties.Backend> list = conf.getLocal();
        if (list == null || list.isEmpty()) return;
        for (LlmProperties.Backend be : list) {
            boolean ok;
            try {
                // Hit the /models endpoint; success indicates the vLLM server is up.
                local.models(be.getBaseUrl()).block(Duration.ofSeconds(2));
                ok = true;
            } catch (Throwable ignore) {
                ok = false;
            }
            String id = be.getId();
            if (id != null && id.endsWith("0")) {
                h0 = ok;
            } else {
                h1 = ok;
            }
        }
    }

    private Flux<String> fallbackStream(String prompt) {
        LlmProperties.Fallback.OpenAi fb = conf.getFallback().getOpenai();
        if (fb == null || !fb.isEnabled()) {
            return Flux.error(new IllegalStateException("All local LLM backends unavailable"));
        }
        // In this sample implementation we simply return an error; a real
        // implementation would call the remote provider here using WebClient.
        return Flux.error(new UnsupportedOperationException("Remote fallback not implemented"));
    }
}