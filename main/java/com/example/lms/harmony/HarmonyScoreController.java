package com.example.lms.harmony;

import com.example.lms.debug.AblationPenaltyBootDumper;
import com.example.lms.search.TraceStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/harmony")
public class HarmonyScoreController {

    private static final AtomicInteger STREAM_ID = new AtomicInteger();

    private final HarmonyScoreEngine engine;

    public HarmonyScoreController(HarmonyScoreEngine engine) {
        this.engine = engine;
    }

    @GetMapping(value = "/score", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HarmonyScoreSnapshot> getScore() {
        return ResponseEntity.ok(computeSnapshot());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(300_000L);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "harmony-score-stream-" + STREAM_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        var future = executor.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("harmony")
                        .data(computeSnapshot(), MediaType.APPLICATION_JSON));
            } catch (IOException error) {
                TraceStore.put("harmony.score.stream.io.catchObserved", Boolean.TRUE);
                recordStreamFailure("io", error);
                emitter.completeWithError(error);
                executor.shutdown();
            } catch (RuntimeException error) {
                TraceStore.put("harmony.score.stream.runtime.catchObserved", Boolean.TRUE);
                recordStreamFailure("runtime", error);
                emitter.completeWithError(error);
                executor.shutdown();
            }
        }, 0L, 30L, TimeUnit.SECONDS);

        Runnable close = () -> {
            future.cancel(false);
            executor.shutdown();
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());
        return emitter;
    }

    @GetMapping(value = "/push", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter push() {
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            emitter.send(SseEmitter.event()
                    .name("harmony")
                    .data(computeSnapshot(), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException error) {
            TraceStore.put("harmony.score.push.io.catchObserved", Boolean.TRUE);
            recordStreamFailure("io", error);
            emitter.completeWithError(error);
        } catch (RuntimeException error) {
            TraceStore.put("harmony.score.push.runtime.catchObserved", Boolean.TRUE);
            recordStreamFailure("runtime", error);
            emitter.completeWithError(error);
        }
        return emitter;
    }

    private HarmonyScoreSnapshot computeSnapshot() {
        AblationPenaltyBootDumper.seedCurrentTrace();
        return engine.compute();
    }

    private static void recordStreamFailure(String failureClass, Throwable error) {
        TraceStore.put("harmony.score.stream.sendFailed", Boolean.TRUE);
        TraceStore.put("harmony.score.stream.failureClass", failureClass);
        TraceStore.put("harmony.score.stream.errorType",
                error == null ? "unknown" : error.getClass().getSimpleName());
    }
}
