package com.example.lms.harmony;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HarmonyTraceReader {

    static final int RECENT_SNAPSHOT_LIMIT = 20;

    private final ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider;

    public HarmonyTraceReader() {
        this.traceSnapshotStoreProvider = null;
    }

    @Autowired
    public HarmonyTraceReader(ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider) {
        this.traceSnapshotStoreProvider = traceSnapshotStoreProvider;
    }

    public TraceRead read(String key) {
        try {
            Object current = TraceStore.get(key);
            if (current != null) {
                return new TraceRead(current, "present");
            }
        } catch (RuntimeException error) {
            TraceStore.put("harmony.trace.currentRead.failed", Boolean.TRUE);
            TraceStore.put("harmony.trace.currentRead.key", key);
            TraceStore.put("harmony.trace.currentRead.errorType", error.getClass().getSimpleName());
        }

        Object recentSnapshot = readRecentSnapshotValue(key);
        if (recentSnapshot != null) {
            return new TraceRead(recentSnapshot, "recentSnapshot");
        }
        return new TraceRead(null, "missing");
    }

    private Object readRecentSnapshotValue(String key) {
        if (traceSnapshotStoreProvider == null) {
            return null;
        }
        try {
            TraceSnapshotStore store = traceSnapshotStoreProvider.getIfAvailable();
            if (store == null) {
                return null;
            }
            List<Map<String, Object>> summaries = store.listSummaries(RECENT_SNAPSHOT_LIMIT);
            if (summaries == null || summaries.isEmpty()) {
                return null;
            }
            for (Map<String, Object> summary : summaries) {
                Object rawId = summary == null ? null : summary.get("id");
                if (rawId == null || String.valueOf(rawId).isBlank()) {
                    continue;
                }
                TraceSnapshotStore.TraceSnapshot snapshot = store.get(String.valueOf(rawId)).orElse(null);
                Map<String, Object> trace = snapshot == null ? null : snapshot.trace();
                if (trace == null || !trace.containsKey(key)) {
                    continue;
                }
                Object value = trace.get(key);
                if (value != null) {
                    return value;
                }
            }
        } catch (RuntimeException error) {
            TraceStore.put("harmony.trace.snapshotRead.failed", Boolean.TRUE);
            TraceStore.put("harmony.trace.snapshotRead.key", key);
            TraceStore.put("harmony.trace.snapshotRead.errorType", error.getClass().getSimpleName());
        }
        return null;
    }

    public record TraceRead(Object value, String evidenceSource) {
    }
}
