package com.example.lms.service.diagnostic;

import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.DegradedStorageWithAck;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.service.VectorStoreService;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates "운영 중 복구/차단 상태" signals into one payload.
 *
 * Intentionally avoids any user-content payloads; counters/state only.
 */
@Service
public class RuntimeDiagnosticsService {

    private final ObjectProvider<VectorStoreService> vectorStoreService;
    private final ObjectProvider<NightmareBreaker> nightmareBreaker;
    private final ObjectProvider<DegradedStorage> outboxStorage;

    public RuntimeDiagnosticsService(
            ObjectProvider<VectorStoreService> vectorStoreService,
            ObjectProvider<NightmareBreaker> nightmareBreaker,
            @Qualifier("outboxStorage") ObjectProvider<DegradedStorage> outboxStorage
    ) {
        this.vectorStoreService = vectorStoreService;
        this.nightmareBreaker = nightmareBreaker;
        this.outboxStorage = outboxStorage;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", Instant.now().toString());

        VectorStoreService vs = vectorStoreService.getIfAvailable();
        if (vs != null) {
            root.put("vectorStore", vs.bufferStats());
        } else {
            root.put("vectorStore", Map.of("available", false));
        }

        NightmareBreaker nb = nightmareBreaker.getIfAvailable();
        if (nb != null) {
            root.put("nightmareBreaker", nb.snapshot());
        } else {
            root.put("nightmareBreaker", Map.of("available", false));
        }

        DegradedStorage outbox = outboxStorage.getIfAvailable();
        if (outbox instanceof DegradedStorageWithAck withAck) {
            root.put("outbox", safeOutboxStats(withAck.stats()));
        } else if (outbox != null) {
            root.put("outbox", Map.of(
                    "available", true,
                    "type", outbox.getClass().getName(),
                    "withAck", false
            ));
        } else {
            root.put("outbox", Map.of("available", false));
        }

        return root;
    }

    private static Map<String, Object> safeOutboxStats(DegradedStorageWithAck.OutboxStats stats) {
        if (stats == null) {
            return Map.of("available", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", stats.enabled());
        out.put("mode", SafeRedactor.traceLabelOrFallback(stats.mode(), ""));
        out.put("pathHash", SafeRedactor.hashValue(stats.path()));
        out.put("pathLength", stats.path() == null ? 0 : stats.path().length());
        out.put("pendingCount", stats.pendingCount());
        out.put("inflightCount", stats.inflightCount());
        out.put("totalBytes", stats.totalBytes());
        out.put("oldestCreatedAt", stats.oldestCreatedAt());
        out.put("newestCreatedAt", stats.newestCreatedAt());
        out.put("maxFiles", stats.maxFiles());
        out.put("maxBytes", stats.maxBytes());
        out.put("ttlSeconds", stats.ttlSeconds());
        out.put("inflightStaleSeconds", stats.inflightStaleSeconds());
        out.put("ackTotal", stats.ackTotal());
        out.put("nackTotal", stats.nackTotal());
        out.put("releaseTotal", stats.releaseTotal());
        out.put("droppedExpiredTotal", stats.droppedExpiredTotal());
        out.put("droppedByLimitTotal", stats.droppedByLimitTotal());
        out.put("parseErrorTotal", stats.parseErrorTotal());
        out.put("lastSweepEpochMs", stats.lastSweepEpochMs());
        return out;
    }
}
