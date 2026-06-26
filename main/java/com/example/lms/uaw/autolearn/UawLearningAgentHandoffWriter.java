package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class UawLearningAgentHandoffWriter {
    private static final System.Logger LOG = System.getLogger(UawLearningAgentHandoffWriter.class.getName());
    private static final int SCHEMA_VERSION = 1;
    private static final int MIN_LINE_BYTES = 512;
    private static final int MAX_LINE_COUNT_SCAN = 10_000;
    private static final String DEFAULT_MANIFEST_PATH = "data/agent-handoff/codex/manifest.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UawAutolearnProperties props;

    public UawLearningAgentHandoffWriter(UawAutolearnProperties props) {
        this.props = props == null ? new UawAutolearnProperties() : props;
    }

    public void recordSample(String sessionId,
                             String datasetName,
                             String question,
                             String answer,
                             String modelUsed,
                             int evidenceCount,
                              UawDatasetWriter.TrainingMetadata metadata,
                              boolean writerOk) {
        recordSampleInternal(sessionId, datasetName, question, answer, modelUsed, evidenceCount, metadata,
                writerOk, null, null);
    }

    public void recordSkippedSample(String sessionId,
                                    String datasetName,
                                    String question,
                                    String answer,
                                    String modelUsed,
                                    int evidenceCount,
                                    UawDatasetWriter.TrainingMetadata metadata,
                                    String outcome,
                                    String failureReason) {
        recordSampleInternal(sessionId, datasetName, question, answer, modelUsed, evidenceCount, metadata,
                false, outcome, failureReason);
    }

    private void recordSampleInternal(String sessionId,
                                      String datasetName,
                                      String question,
                                      String answer,
                                      String modelUsed,
                                      int evidenceCount,
                                      UawDatasetWriter.TrainingMetadata metadata,
                                      boolean writerOk,
                                      String explicitOutcome,
                                      String explicitFailureReason) {
        UawAutolearnProperties.AgentHandoff cfg = cfg();
        if (!cfg.isEnabled()) {
            return;
        }
        try {
            LearningSampleValidationMetadata validation = metadata == null ? null : metadata.validation();
            String decision = sampleDecision(validation, writerOk, explicitOutcome);
            boolean accepted = "ACCEPTED".equals(decision);
            Path target = path(accepted ? cfg.getAcceptedPath() : cfg.getRejectedPath(), accepted ? "accepted.jsonl" : "rejected.jsonl");
            ObjectNode node = objectMapper.createObjectNode();
            node.put("schemaVersion", SCHEMA_VERSION);
            node.put("type", "uaw_autolearn_sample");
            node.put("ts", Instant.now().toString());
            node.put("decision", decision);
            node.put("sampleHash", sampleHash(question, answer, modelUsed));
            node.put("questionHash", hashOrEmpty(question));
            node.put("answerHash", hashOrEmpty(answer));
            node.put("outcome", sampleOutcome(decision, explicitOutcome));
            node.put("failureReason", safeReason(failureReason(validation, decision, explicitFailureReason)));
            node.put("hasSessionId", sessionId != null && !sessionId.isBlank());
            node.put("sessionHash", hashOrEmpty(sessionId));
            node.put("dataset", safeScalar(datasetName, 120));
            node.put("model", safeScalar(modelUsed, 120));
            node.put("branch", safeScalar(metadata == null ? "uaw_autolearn" : metadata.branch(), 120));
            node.put("provider", safeScalar(metadata == null ? "" : metadata.provider(), 120));
            node.put("disabledReason", safeReason(metadata == null ? "" : metadata.disabledReason()));
            node.put("evidenceCount", Math.max(0, evidenceCount));
            node.put("afterFilterCount", metadata == null ? 0 : Math.max(0, metadata.afterFilterCount()));
            node.put("finalGate", metadata != null && metadata.finalGate());
            node.put("writerOk", writerOk);
            node.put("contextDiversity", metadata == null ? 0.0d : metadata.contextDiversity());

            boolean fullAccepted = accepted && cfg.isWriteFullAcceptedText();
            int previewChars = Math.max(160, cfg.getRejectedPreviewChars());
            node.put("questionPreview", safePreview(question, fullAccepted ? 8_000 : previewChars));
            node.put("answerPreview", safePreview(answer, fullAccepted ? 12_000 : previewChars));
            node.put("containsFullText", fullAccepted);
            node.set("validation", validationNode(validation));
            node.set("traceHints", traceHints());
            appendLine(target, node, cfg.getMaxLineBytes());
            writeManifest(null);
            TraceStore.put("uaw.agent.handoff.status", "sample_recorded");
            TraceStore.put("uaw.agent.handoff.lastDecision", decision);
            TraceStore.put("uaw.agent.handoff.lastSampleHash", node.get("sampleHash").asText());
        } catch (Exception e) {
            TraceStore.put("uaw.agent.handoff.status", "sample_write_failed");
            TraceStore.put("uaw.agent.handoff.error", SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
        }
    }

    public void recordCycle(String sessionId,
                            String datasetPath,
                            AutoLearnCycleResult result,
                            UawAutolearnQualityTracker.CycleDiagnostics cycle) {
        UawAutolearnProperties.AgentHandoff cfg = cfg();
        if (!cfg.isEnabled() || result == null) {
            return;
        }
        try {
            ObjectNode node = cycleNode(sessionId, datasetPath, result, cycle);
            appendLine(path(cfg.getCyclePath(), "cycles.jsonl"), node, cfg.getMaxLineBytes());
            writeManifest(node);
            TraceStore.put("uaw.agent.handoff.status", "cycle_recorded");
            TraceStore.put("uaw.agent.handoff.manifestPathHash", hashOrEmpty(cfg.getManifestPath()));
        } catch (Exception e) {
            TraceStore.put("uaw.agent.handoff.status", "cycle_write_failed");
            TraceStore.put("uaw.agent.handoff.error", SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
        }
    }

    public Map<String, Object> manifestSummary() {
        UawAutolearnProperties.AgentHandoff cfg = cfg();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", SCHEMA_VERSION);
        out.put("enabled", cfg.isEnabled());
        out.put("disabledReason", cfg.isEnabled() ? "" : "disabled_by_config");
        out.put("rootDirHash", fileNameHash(cfg.getRootPath()));
        out.put("rootDirLength", fileNameLength(cfg.getRootPath()));
        out.put("rootPathHash", hashOrEmpty(cfg.getRootPath()));
        out.put("writeFullAcceptedText", cfg.isWriteFullAcceptedText());
        out.put("maxLineBytes", Math.max(MIN_LINE_BYTES, cfg.getMaxLineBytes()));

        Map<String, Object> files = new LinkedHashMap<>();
        files.put("accepted", fileSummary(path(cfg.getAcceptedPath(), "accepted.jsonl"), true));
        files.put("rejected", fileSummary(path(cfg.getRejectedPath(), "rejected.jsonl"), true));
        files.put("cycles", fileSummary(path(cfg.getCyclePath(), "cycles.jsonl"), true));
        files.put("manifest", fileSummary(path(cfg.getManifestPath(), "manifest.json"), false));
        out.put("files", files);
        return out;
    }

    private ObjectNode cycleNode(String sessionId,
                                 String datasetPath,
                                 AutoLearnCycleResult result,
                                 UawAutolearnQualityTracker.CycleDiagnostics cycle) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaVersion", SCHEMA_VERSION);
        node.put("type", "uaw_autolearn_cycle");
        node.put("ts", Instant.now().toString());
        node.put("hasSessionId", sessionId != null && !sessionId.isBlank());
        node.put("sessionHash", hashOrEmpty(sessionId));
        node.put("datasetFileHash", fileNameHash(datasetPath));
        node.put("datasetFileLength", fileNameLength(datasetPath));
        node.put("datasetPathHash", hashOrEmpty(datasetPath));
        node.put("attempted", result.attempted());
        node.put("accepted", result.acceptedCount());
        node.put("abortedByUser", result.abortedByUser());
        node.put("errorCount", result.errorCount());
        node.put("errorRate", result.errorRate());
        node.put("errorRateWindow", result.errorRateWindow());
        node.put("thresholdTuningDelta", result.thresholdTuningDelta());
        node.put("trainAllowed", result.trainAllowed());
        node.put("trainDecision", safeScalar(result.trainDecision(), 120));
        node.put("topProblem", safeScalar(result.topProblem(), 120));
        node.put("dominantFailure", safeScalar(result.dominantFailure(), 120));
        node.put("diagnosis", safeScalar(result.diagnosis(), 180));
        node.set("phaseFailures", objectMapper.valueToTree(result.phaseFailures()));
        if (cycle != null) {
            ObjectNode cycleNode = objectMapper.createObjectNode();
            cycleNode.put("errorRateWindow", cycle.errorRateWindow());
            cycleNode.put("tuningDelta", cycle.tuningDelta());
            cycleNode.put("trainAllowed", cycle.trainAllowed());
            cycleNode.put("topProblem", safeScalar(cycle.topProblem(), 120));
            cycleNode.put("trainDecision", safeScalar(cycle.trainDecision(), 120));
            node.set("qualityCycle", cycleNode);
        }
        return node;
    }

    private void writeManifest(ObjectNode latestCycle) throws Exception {
        UawAutolearnProperties.AgentHandoff cfg = cfg();
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("updatedAt", Instant.now().toString());
        manifest.put("enabled", cfg.isEnabled());
        manifest.put("rootDirHash", fileNameHash(cfg.getRootPath()));
        manifest.put("rootDirLength", fileNameLength(cfg.getRootPath()));
        manifest.put("rootPathHash", hashOrEmpty(cfg.getRootPath()));
        manifest.put("rawDatasetIsDb", false);
        manifest.put("vectorDbIsTrainingSource", false);
        manifest.put("reviewInstruction", "Read manifest.json, cycles.jsonl, and rejected.jsonl before source edits. Do not overwrite train_rag.jsonl.");
        ArrayNode priorityFiles = objectMapper.createArrayNode();
        priorityFiles.add(DEFAULT_MANIFEST_PATH);
        priorityFiles.add("data/agent-handoff/codex/cycles.jsonl");
        priorityFiles.add("data/agent-handoff/codex/rejected.jsonl");
        priorityFiles.add("data/agent-handoff/codex/accepted.jsonl");
        manifest.set("priorityFiles", priorityFiles);
        manifest.set("summary", objectMapper.valueToTree(manifestSummary()));
        if (latestCycle != null) {
            manifest.set("latestCycle", latestCycle);
        }
        Path target = path(cfg.getManifestPath(), "manifest.json");
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);
    }

    private void appendLine(Path target, ObjectNode node, int configuredMaxBytes) throws Exception {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String line = jsonWithinLimit(node, configuredMaxBytes);
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            writer.write(line);
            writer.newLine();
        }
    }

    private String jsonWithinLimit(ObjectNode node, int configuredMaxBytes) throws Exception {
        int maxBytes = Math.max(MIN_LINE_BYTES, configuredMaxBytes);
        String line = objectMapper.writeValueAsString(node);
        if (line.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return line;
        }
        node.put("truncated", true);
        node.put("questionPreview", "(truncated)");
        node.put("answerPreview", "(truncated)");
        node.remove("traceHints");
        line = objectMapper.writeValueAsString(node);
        if (line.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return line;
        }
        node.remove("validation");
        line = objectMapper.writeValueAsString(node);
        if (line.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return line;
        }
        ObjectNode minimal = objectMapper.createObjectNode();
        minimal.put("schemaVersion", SCHEMA_VERSION);
        minimal.put("type", node.path("type").asText("uaw_autolearn_event"));
        minimal.put("ts", node.path("ts").asText(Instant.now().toString()));
        minimal.put("decision", node.path("decision").asText("TRUNCATED"));
        minimal.put("sampleHash", node.path("sampleHash").asText(""));
        minimal.put("sessionHash", node.path("sessionHash").asText(""));
        minimal.put("truncated", true);
        minimal.put("truncatedReason", "max_line_bytes");
        return objectMapper.writeValueAsString(minimal);
    }

    private ObjectNode validationNode(LearningSampleValidationMetadata validation) {
        ObjectNode node = objectMapper.createObjectNode();
        if (validation == null) {
            node.put("accepted", false);
            node.set("rejectReasons", stringArray(List.of("missing_validation_metadata")));
            return node;
        }
        node.put("accepted", validation.accepted());
        node.put("questionType", safeScalar(validation.questionType(), 120));
        node.set("selfAskLanes", stringArray(validation.selfAskLanes()));
        node.put("selfAskLaneCoverage", validation.selfAskLaneCoverage());
        node.put("riskScore", validation.riskScore());
        node.put("contradictionScore", validation.contradictionScore());
        node.put("contradictionCause", safeScalar(validation.contradictionCause(), 120));
        node.put("contaminationScore", validation.contaminationScore());
        node.put("legacyContextScore", validation.legacyContextScore());
        node.put("contextContaminationScore", validation.contextContaminationScore());
        node.put("sampleScore", validation.sampleScore());
        node.set("rejectReasons", stringArray(validation.rejectReasons()));
        node.set("evaluationCriteria", stringArray(validation.evaluationCriteria()));
        node.set("thresholds", objectMapper.valueToTree(validation.thresholds()));
        node.set("runtime", objectMapper.valueToTree(validation.runtime()));
        node.set("anomalies", objectMapper.valueToTree(validation.anomalies()));
        node.set("feedback", objectMapper.valueToTree(validation.feedback()));
        return node;
    }

    private ObjectNode traceHints() {
        ObjectNode node = objectMapper.createObjectNode();
        Map<String, Object> trace = TraceStore.getAll();
        for (String key : List.of(
                "learning.validation.decision",
                "learning.validation.rejectReasons",
                "learning.validation.thresholdBreaks",
                "learning.loop.errorRate",
                "learning.loop.dominantFailure",
                "blackbox.risk.dominantFailure",
                "uaw.retrain.ingest.summary")) {
            if (trace.containsKey(key)) {
                node.set(key, objectMapper.valueToTree(SafeRedactor.diagnosticValue(key, trace.get(key), 600)));
            }
        }
        return node;
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        if (values != null) {
            for (String value : values) {
                array.add(safeScalar(value, 180));
            }
        }
        return array;
    }

    private Map<String, Object> fileSummary(Path path, boolean countLines) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileNameHash", path == null ? "" : fileNameHash(path.toString()));
        out.put("fileNameLength", path == null ? 0 : fileNameLength(path.toString()));
        out.put("pathHash", path == null ? "" : hashOrEmpty(path.toString()));
        boolean exists = path != null && Files.exists(path);
        out.put("exists", exists);
        if (!exists) {
            out.put("sizeBytes", 0L);
            return out;
        }
        try {
            out.put("sizeBytes", Files.size(path));
            out.put("lastModified", Files.getLastModifiedTime(path).toInstant().toString());
            if (countLines) {
                try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
                    long count = lines.limit(MAX_LINE_COUNT_SCAN + 1L).count();
                    out.put("lineCount", Math.min(count, MAX_LINE_COUNT_SCAN));
                    out.put("lineCountTruncated", count > MAX_LINE_COUNT_SCAN);
                }
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "[AWX][uaw][handoff] dataset summary skipped path redacted");
            out.put("error", SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
        }
        return out;
    }

    private UawAutolearnProperties.AgentHandoff cfg() {
        UawAutolearnProperties.AgentHandoff cfg = props.getAgentHandoff();
        return cfg == null ? new UawAutolearnProperties.AgentHandoff() : cfg;
    }

    private static String sampleDecision(LearningSampleValidationMetadata validation,
                                         boolean writerOk,
                                         String explicitOutcome) {
        if (explicitOutcome != null && !explicitOutcome.isBlank()) {
            String normalized = explicitOutcome.trim().toUpperCase(java.util.Locale.ROOT);
            if ("ERROR".equals(normalized) || "SKIPPED".equals(normalized)) {
                return normalized;
            }
        }
        if (validation == null) {
            return "REJECTED_MISSING_VALIDATION";
        }
        if (quarantined(validation)) {
            return "QUARANTINE";
        }
        if (!validation.accepted()) {
            return "REJECTED";
        }
        return writerOk ? "ACCEPTED" : "WRITER_FAILED";
    }

    private static boolean quarantined(LearningSampleValidationMetadata validation) {
        if (validation == null) {
            return false;
        }
        if (validation.anomalies() != null && !validation.anomalies().flags().isEmpty()) {
            return true;
        }
        return validation.feedback() != null
                && "QUARANTINE".equalsIgnoreCase(validation.feedback().vectorDecision());
    }

    private static String sampleOutcome(String decision, String explicitOutcome) {
        if (explicitOutcome != null && !explicitOutcome.isBlank()) {
            return explicitOutcome.trim().toUpperCase(java.util.Locale.ROOT);
        }
        if ("ACCEPTED".equals(decision)) {
            return "ACCEPTED";
        }
        if ("WRITER_FAILED".equals(decision)) {
            return "ERROR";
        }
        return "REJECTED";
    }

    private static String failureReason(LearningSampleValidationMetadata validation,
                                        String decision,
                                        String explicitFailureReason) {
        if (explicitFailureReason != null && !explicitFailureReason.isBlank()) {
            return explicitFailureReason;
        }
        if ("ACCEPTED".equals(decision)) {
            return "";
        }
        if ("WRITER_FAILED".equals(decision)) {
            return "writer_failed";
        }
        if ("QUARANTINE".equals(decision)) {
            if (validation != null && validation.feedback() != null
                    && "QUARANTINE".equalsIgnoreCase(validation.feedback().vectorDecision())) {
                return "vector_quarantine";
            }
            return "validation_anomaly";
        }
        if (validation == null || validation.rejectReasons().isEmpty()) {
            return "missing_validation_metadata";
        }
        return validation.rejectReasons().get(0);
    }

    private static Path path(String configured, String fallbackFile) {
        String value = configured == null || configured.isBlank()
                ? "data/agent-handoff/codex/" + fallbackFile
                : configured.trim();
        return Path.of(value);
    }

    private static String sampleHash(String question, String answer, String modelUsed) {
        String hash = SafeRedactor.hashValue((question == null ? "" : question)
                + '\0' + (answer == null ? "" : answer)
                + '\0' + (modelUsed == null ? "" : modelUsed));
        return hash == null ? "" : hash;
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static String fileNameHash(String path) {
        String fileName = fileName(path);
        return fileName.isBlank() ? "" : hashOrEmpty(fileName);
    }

    private static int fileNameLength(String path) {
        return fileName(path).length();
    }

    private static String safePreview(String value, int maxChars) {
        String safe = SafeRedactor.safeMessage(value == null ? "" : value, Math.max(80, maxChars));
        if (safe == null) {
            return "";
        }
        safe = safe.replaceAll("(?i)\\bBearer\\s+[^\\s,;]+", "<redacted-bearer>");
        safe = safe.replaceAll("(?i)\\b(?:authorization|ownertoken|api[-_]?key|client[-_]?secret|secret|token|password)\\s*[:=]\\s*[^\\s,;]+",
                "<redacted-secret>");
        return safe;
    }

    private static String safeScalar(String value, int maxChars) {
        String safe = safePreview(value, maxChars);
        return safe == null ? "" : safe;
    }

    private static String safeReason(String value) {
        return SafeRedactor.traceLabelOrFallback(value, "");
    }

    private static String fileName(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (p.isEmpty()) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        return idx >= 0 && idx + 1 < p.length() ? p.substring(idx + 1) : p;
    }
}
