package com.example.lms.service.rag.offline;

import ai.abandonware.nova.orch.anchor.AnchorNarrowingResult;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class OfflineTextureSnapshotWriter {
    private static final Logger log = LoggerFactory.getLogger(OfflineTextureSnapshotWriter.class);
    private final OfflineTextureProperties properties;
    private final ObjectMapper objectMapper;

    public OfflineTextureSnapshotWriter(OfflineTextureProperties properties, ObjectMapper objectMapper) {
        this.properties = properties == null ? new OfflineTextureProperties() : properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public void writeFrom(String queryHash, AnchorNarrowingResult anchorResult, Map<String, Object> trace) {
        if (!properties.isEnabled() || !properties.isWriteEnabled()) {
            traceWriteStatus("write_disabled");
            return;
        }
        String safeQueryHash = safeSnapshotQueryToken(queryHash);
        if (safeQueryHash.isBlank()) {
            safeQueryHash = "noquery";
        }
        Instant now = Instant.now();
        String snapshotId = "ot-" + safeQueryHash + "-" + Long.toUnsignedString(now.toEpochMilli(), 36);
        Instant expires = now.plusSeconds(Math.max(1, properties.getTtlHours()) * 3600L);
        Map<String, Object> safeTrace = trace == null ? Map.of() : trace;

        OfflineTextureSnapshot snapshot = new OfflineTextureSnapshot(
                snapshotId,
                1,
                domainFromTrace(safeTrace),
                now.toString(),
                expires.toString(),
                safeAnchorHashes(anchorResult, safeTrace),
                safeEntityKeys(safeTrace),
                safeChunkIds(safeTrace),
                routePriors(safeTrace),
                sourcePriors(safeTrace),
                safeMap(safeTrace.get("rag.eval.kgAxis")),
                fusionStats(safeTrace),
                failureSignatures(safeTrace));
        try {
            Path snapshotDir = Path.of(properties.getSnapshotDir());
            Path snapshotPath = snapshotDir.resolve(snapshotId + ".json");
            Path manifest = Path.of(properties.getManifestPath());
            String relativeSnapshotPath = relativeSnapshotPath(manifest, snapshotPath);
            if (relativeSnapshotPath.isBlank()) {
                traceWrite("write_error:relative_path_unavailable", snapshot);
                return;
            }

            Files.createDirectories(snapshotDir);
            Files.writeString(snapshotPath, objectMapper.writeValueAsString(snapshot), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);

            if (manifest.getParent() != null) {
                Files.createDirectories(manifest.getParent());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("snapshotId", snapshot.snapshotId());
            row.put("schemaVersion", snapshot.schemaVersion());
            row.put("createdAt", snapshot.createdAt());
            row.put("expiresAt", snapshot.expiresAt());
            row.put("snapshotPath", relativeSnapshotPath);
            row.put("anchorCount", snapshot.anchors().size());
            row.put("failureSignatureCount", snapshot.failureSignatures().size());
            Files.writeString(manifest, objectMapper.writeValueAsString(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            trimManifest(manifest);
            traceWrite("ok", snapshot);
        } catch (Exception e) {
            log.debug("[OfflineTexture] fail-soft stage=write.snapshot err=write-failure");
            traceWrite("write_error:offline_texture_write_failed", snapshot);
        }
    }

    private void trimManifest(Path manifest) throws Exception {
        int max = Math.max(1, properties.getMaxSnapshots());
        if (!Files.exists(manifest)) {
            return;
        }
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.size() <= max) {
            return;
        }
        List<String> removed = List.copyOf(lines.subList(0, lines.size() - max));
        List<String> keep = List.copyOf(lines.subList(lines.size() - max, lines.size()));
        cleanupRemovedSnapshots(manifest, removed);
        Files.write(manifest, keep, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(manifest, System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static void traceWrite(String status, OfflineTextureSnapshot snapshot) {
        try {
            traceWriteStatus(status);
            TraceStore.put("rag.offlineTexture.write.snapshotId", snapshot.snapshotId());
            TraceStore.put("rag.offlineTexture.write.anchorCount", snapshot.anchors().size());
            TraceStore.put("rag.offlineTexture.write.failureSignatureCount", snapshot.failureSignatures().size());
        } catch (Throwable ignore) {
            log.debug("[OfflineTexture] fail-soft stage=write.trace err=trace-failure");
        }
    }

    private static void traceWriteStatus(String status) {
        try {
            TraceStore.put("rag.offlineTexture.write.status", status);
        } catch (Throwable ignore) {
            log.debug("[OfflineTexture] fail-soft stage=write.statusTrace err=trace-failure");
        }
    }

    private static List<String> safeAnchorHashes(AnchorNarrowingResult result, Map<String, Object> trace) {
        List<String> out = new ArrayList<>();
        if (result != null && result.anchors() != null) {
            for (String anchor : result.anchors()) {
                String hash = SafeRedactor.hash12(anchor);
                if (hash != null && !hash.isBlank()) {
                    out.add(hash);
                }
                if (out.size() >= 3) {
                    return List.copyOf(out);
                }
            }
        }
        Object tracedAnchors = trace == null ? null : trace.get("rag.anchor.anchors");
        if (tracedAnchors instanceof Iterable<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    addSafe(out, map.get("hash12"));
                } else {
                    addSafe(out, row);
                }
                if (out.size() >= 3) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<String> safeEntityKeys(Map<String, Object> trace) {
        Set<String> out = new LinkedHashSet<>();
        Object kgAxis = trace.get("rag.eval.kgAxis");
        if (kgAxis instanceof Map<?, ?> map) {
            addSafe(out, map.get("dependencyStatus"));
            addSafe(out, map.get("neo4jStatus"));
            addSafe(out, map.get("jpaStatus"));
            addSafe(out, map.get("sparsePathStatus"));
        }
        return List.copyOf(out);
    }

    private static List<String> safeChunkIds(Map<String, Object> trace) {
        Object ids = trace.get("rag.evidence.chunkIds");
        if (!(ids instanceof Iterable<?> rows)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object row : rows) {
            String hash = SafeRedactor.hash12(String.valueOf(row));
            if (hash != null && !hash.isBlank()) {
                out.add(hash);
            }
            if (out.size() >= 32) {
                break;
            }
        }
        return out;
    }

    private static Map<String, Double> routePriors(Map<String, Object> trace) {
        Map<String, Double> out = new LinkedHashMap<>();
        putDouble(out, "suggestedWebTopK", trace.get("rag.budget.suggested.webTopK"));
        putDouble(out, "suggestedVectorTopK", trace.get("rag.budget.suggested.vectorTopK"));
        putDouble(out, "suggestedKgTopK", trace.get("rag.budget.suggested.kgTopK"));
        putDouble(out, "appliedQueryBurstCount", trace.get("rag.budget.applied.queryBurstCount"));
        putDouble(out, "textureHitRate", trace.get("rag.metrics.textureHitRate"));
        putDouble(out, "anchorPrecisionProxy", trace.get("rag.metrics.anchorPrecisionProxy"));
        return out;
    }

    private static Map<String, Double> sourcePriors(Map<String, Object> trace) {
        Map<String, Double> out = new LinkedHashMap<>();
        Object sourceDiversity = trace.get("rag.eval.sourceDiversity");
        if (sourceDiversity instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String keyHash = SafeRedactor.hash12(String.valueOf(entry.getKey()));
                if (keyHash != null && !keyHash.isBlank()) {
                    putDouble(out, "source:" + keyHash, entry.getValue());
                }
                if (out.size() >= 32) {
                    break;
                }
            }
        }
        return out;
    }

    private static Map<String, Object> fusionStats(Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of(
                "rag.fusion.sizes.selfask",
                "rag.fusion.sizes.web",
                "rag.fusion.sizes.vector",
                "rag.fusion.sizes.kg",
                "rag.fusion.final.kgCount",
                "rag.fusion.final.totalCount",
                "rag.budget.applied.queryBurstCount",
                "rag.budget.suggested.webTopK",
                "rag.budget.suggested.vectorTopK",
                "rag.budget.suggested.kgTopK",
                "rag.budget.suggested.enableHypernova",
                "rag.budget.suggested.enableMassiveExpansion",
                "rag.budget.reason",
                "rag.metrics.anchorPrecisionProxy",
                "rag.metrics.textureHitRate",
                "rag.metrics.burstReductionRate",
                "rag.metrics.kgRetention",
                "rag.metrics.offlinePredictionUsed")) {
            if (trace.containsKey(key)) {
                out.put(key, SafeRedactor.diagnosticValue(key, trace.get(key), 160));
            }
        }
        return out;
    }

    private static List<String> failureSignatures(Map<String, Object> trace) {
        Set<String> out = new LinkedHashSet<>();
        Object kgAxis = trace.get("rag.eval.kgAxis");
        if (kgAxis instanceof Map<?, ?> map) {
            addIterable(out, map.get("signals"));
        }
        Object breaks = trace.get("rag.eval.thresholdBreaks");
        if (breaks instanceof Iterable<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    addSafe(out, map.get("label"));
                    addSafe(out, map.get("reasonCode"));
                } else {
                    addSafe(out, row);
                }
            }
        }
        addSafe(out, trace.get("langgraph.node.quality_gate.reason"));
        addSafe(out, trace.get("langgraph.qualityGate.failureReason"));
        return List.copyOf(out);
    }

    private static Map<String, Object> safeMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of(
                "schemaVersion",
                "status",
                "retrievedCount",
                "finalCount",
                "finalRetention",
                "score",
                "kgPoolCount",
                "kgFinalCount",
                "kgScoreMean",
                "kgScoreP95",
                "kgFinalRetention",
                "graphScore",
                "graphOpportunity",
                "signals",
                "emptyReason")) {
            if (map.containsKey(key)) {
                out.put(key, SafeRedactor.diagnosticValue("rag.eval.kgAxis." + key, map.get(key), 160));
            }
        }
        return out;
    }

    private static String domainFromTrace(Map<String, Object> trace) {
        Object fp = trace.get("rag.eval.queryFingerprint");
        if (fp instanceof Map<?, ?> map) {
            Object domain = map.get("domain");
            if (domain != null && !String.valueOf(domain).isBlank()) {
                return safeToken(String.valueOf(domain)).toUpperCase(Locale.ROOT);
            }
        }
        return "GENERAL";
    }

    private static void putDouble(Map<String, Double> out, String key, Object value) {
        double d = toDouble(value, Double.NaN);
        if (!Double.isNaN(d) && !Double.isInfinite(d)) {
            out.put(key, d);
        }
    }

    private static double toDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignore) {
                log.debug("[OfflineTexture] fail-soft stage=number.parse err=parse-failure"); return fallback;
            }
        }
        return fallback;
    }

    private static void addIterable(Set<String> out, Object raw) {
        if (raw instanceof Iterable<?> rows) {
            for (Object row : rows) {
                addSafe(out, row);
            }
        } else {
            addSafe(out, raw);
        }
    }

    private static void addSafe(java.util.Collection<String> out, Object raw) {
        if (raw == null || out.size() >= 32) {
            return;
        }
        String safe = safeToken(String.valueOf(raw));
        if (!safe.isBlank()) {
            out.add(safe);
        }
    }

    private static String safeToken(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:-]+", "_");
        if (s.length() > 80) {
            s = s.substring(0, 80);
        }
        return s;
    }

    private static String safeSnapshotQueryToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String safe = safeToken(raw);
        if (safe.matches("hash:[0-9a-f]{8,64}") || safe.matches("[0-9a-f]{8,64}")) {
            return safe;
        }
        String hash = SafeRedactor.hash12(raw);
        return hash == null ? "" : hash;
    }

    private void cleanupRemovedSnapshots(Path manifest, List<String> removedLines) {
        if (removedLines == null || removedLines.isEmpty()) {
            return;
        }
        Path snapshotRoot = Path.of(properties.getSnapshotDir()).toAbsolutePath().normalize();
        for (String line : removedLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = objectMapper.readValue(line, Map.class);
                Object rawPath = row.get("snapshotPath");
                if (!(rawPath instanceof String path) || path.isBlank()) {
                    continue;
                }
                Path snapshotPath = resolveManifestSnapshotPath(manifest, path).toAbsolutePath().normalize();
                if (!snapshotPath.startsWith(snapshotRoot)) {
                    TraceStore.put("rag.offlineTexture.write.orphanCleanup", "skipped:path_outside_snapshot_dir");
                    continue;
                }
                Files.deleteIfExists(snapshotPath);
            } catch (Exception e) {
                TraceStore.put("rag.offlineTexture.write.orphanCleanup", "skipped:offline_texture_orphan_cleanup_failed");
            } catch (Throwable ignore) {
                log.debug("[OfflineTexture] fail-soft stage=orphan.cleanup err=cleanup-failure");
            }
        }
    }

    private static Path resolveManifestSnapshotPath(Path manifest, String path) {
        Path snapshotPath = Path.of(path);
        if (snapshotPath.isAbsolute()) {
            return snapshotPath;
        }
        Path manifestParent = manifest == null ? null : manifest.getParent();
        return manifestParent == null ? snapshotPath.normalize() : manifestParent.resolve(snapshotPath).normalize();
    }

    private static String relativeSnapshotPath(Path manifest, Path snapshotPath) {
        if (snapshotPath == null) {
            return "";
        }
        try {
            Path manifestParent = manifest == null ? null : manifest.getParent();
            Path relative;
            if (manifestParent == null) {
                if (snapshotPath.isAbsolute()) {
                    return "";
                }
                relative = snapshotPath.normalize();
            } else {
                relative = manifestParent.toAbsolutePath().normalize()
                        .relativize(snapshotPath.toAbsolutePath().normalize());
            }
            String out = relative.normalize().toString().replace('\\', '/');
            return out.startsWith("..") ? "" : out;
        } catch (Exception ignore) {
            log.debug("[OfflineTexture] fail-soft stage=relative.path err=path-failure"); return "";
        }
    }
}
