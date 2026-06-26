package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UawLearningAgentHandoffWriterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void recordSampleWritesRedactedJsonlAndManifest() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String supabaseSecret = "sb_secret_" + "handoffpreview123456";
        String question = "How to debug? sk-1234567890abcdef ownerToken=secret-value " + supabaseSecret;
        String answer = "Use diagnostics. Bearer " + "abc.def.ghi password=plain-text " + supabaseSecret;

        writer.recordSample(
                "session-raw-123",
                "uaw-train",
                question,
                answer,
                "gemma4:26b",
                3,
                metadata(LearningSampleValidationMetadata.empty(), true),
                true);

        Path accepted = tempDir.resolve("handoff/accepted.jsonl");
        Path manifest = tempDir.resolve("handoff/manifest.json");
        assertTrue(Files.exists(accepted));
        assertTrue(Files.exists(manifest));

        String line = Files.readString(accepted, StandardCharsets.UTF_8);
        JsonNode node = objectMapper.readTree(line);
        assertEquals("ACCEPTED", node.path("decision").asText());
        assertEquals(SafeRedactor.hashValue("session-raw-123"), node.path("sessionHash").asText());
        assertEquals(SafeRedactor.hashValue(question), node.path("questionHash").asText());
        assertEquals(SafeRedactor.hashValue(answer), node.path("answerHash").asText());
        assertEquals("ACCEPTED", node.path("outcome").asText());
        assertFalse(line.contains("sk-1234567890abcdef"));
        assertFalse(line.contains("ownerToken"));
        assertFalse(line.contains("secret-value"));
        assertFalse(line.contains("Bearer " + "abc.def.ghi"));
        assertFalse(line.contains("password=plain-text"));
        assertFalse(line.contains(supabaseSecret));
        assertFalse(line.contains("sb_secret_"));
        JsonNode manifestNode = objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        assertTrue(manifestNode.path("priorityFiles").toString().contains("rejected.jsonl"));
        assertFalse(manifestNode.path("rawDatasetIsDb").asBoolean(true));
        assertFalse(manifestNode.path("vectorDbIsTrainingSource").asBoolean(true));
        assertTrue(manifestNode.path("reviewInstruction").asText().contains("Do not overwrite train_rag.jsonl"));
        assertFalse(manifestNode.has("rootDir"));
        assertEquals(SafeRedactor.hashValue("handoff"), manifestNode.path("rootDirHash").asText());
        assertEquals("handoff".length(), manifestNode.path("rootDirLength").asInt());

        Map<String, Object> summary = writer.manifestSummary();
        assertFalse(summary.containsKey("rootDir"));
        assertEquals(SafeRedactor.hashValue("handoff"), summary.get("rootDirHash"));
        assertEquals("handoff".length(), summary.get("rootDirLength"));
        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) summary.get("files");
        @SuppressWarnings("unchecked")
        Map<String, Object> acceptedSummary = (Map<String, Object>) files.get("accepted");
        assertFalse(acceptedSummary.containsKey("fileName"));
        assertEquals(SafeRedactor.hashValue("accepted.jsonl"), acceptedSummary.get("fileNameHash"));
        assertEquals("accepted.jsonl".length(), acceptedSummary.get("fileNameLength"));
        assertEquals(true, acceptedSummary.get("exists"));
    }

    @Test
    void recordSampleClassifiesQuarantineBeforeWriterFailure() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        LearningSampleValidationMetadata quarantine = LearningSampleValidationMetadata.empty()
                .withFeedback(new LearningSampleValidationMetadata.Feedback(0.0d, "QUARANTINE"));

        writer.recordSample(
                "session-raw-123",
                "uaw-train",
                "question",
                "answer",
                "gemma4:26b",
                3,
                metadata(quarantine, true),
                false);

        String line = Files.readString(tempDir.resolve("handoff/rejected.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode node = objectMapper.readTree(line);
        assertEquals("QUARANTINE", node.path("decision").asText());
        assertEquals("REJECTED", node.path("outcome").asText());
        assertEquals("vector_quarantine", node.path("failureReason").asText());
    }

    @Test
    void recordSkippedSampleKeepsOutcomeAndFailureReason() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);

        writer.recordSkippedSample(
                "session-raw-123",
                "uaw-train",
                "question",
                "",
                "",
                0,
                null,
                "SKIPPED",
                "insufficient_evidence");

        String line = Files.readString(tempDir.resolve("handoff/rejected.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode node = objectMapper.readTree(line);
        assertEquals("SKIPPED", node.path("decision").asText());
        assertEquals("SKIPPED", node.path("outcome").asText());
        assertEquals("insufficient_evidence", node.path("failureReason").asText());
    }

    @Test
    void recordSampleHashesUnsafeDisabledReason() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String unsafeReason = "ownerToken=" + "sk-" + "handoffredaction1234567890";

        writer.recordSample(
                "session-raw-123",
                "uaw-train",
                "question",
                "answer",
                "gemma4:26b",
                3,
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn",
                        "local",
                        unsafeReason,
                        3,
                        true,
                        0.90d,
                        LearningSampleValidationMetadata.empty()),
                true);

        String line = Files.readString(tempDir.resolve("handoff/accepted.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode node = objectMapper.readTree(line);
        String disabledReason = node.path("disabledReason").asText();
        assertTrue(disabledReason.startsWith("hash:"), disabledReason);
        assertFalse(line.contains("ownerToken"));
        assertFalse(line.contains("handoffredaction1234567890"));
    }

    @Test
    void recordSkippedSampleHashesUnsafeExplicitFailureReason() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String unsafeReason = "client_secret=" + "sk-" + "handofffailure1234567890";

        writer.recordSkippedSample(
                "session-raw-123",
                "uaw-train",
                "question",
                "",
                "",
                0,
                null,
                "SKIPPED",
                unsafeReason);

        String line = Files.readString(tempDir.resolve("handoff/rejected.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode node = objectMapper.readTree(line);
        String failureReason = node.path("failureReason").asText();
        assertTrue(failureReason.startsWith("hash:"), failureReason);
        assertFalse(line.contains("client_secret"));
        assertFalse(line.contains("handofffailure1234567890"));
    }

    @Test
    void recordSampleEnforcesMaxLineBytes() throws Exception {
        UawAutolearnProperties props = props();
        props.getAgentHandoff().setMaxLineBytes(512);
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String large = "x".repeat(10_000);

        writer.recordSample(
                "session-raw-123",
                "uaw-train",
                large,
                large,
                "gemma4:26b",
                3,
                metadata(LearningSampleValidationMetadata.empty(), true),
                true);

        String line = Files.readString(tempDir.resolve("handoff/accepted.jsonl"), StandardCharsets.UTF_8).trim();
        assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 512);
        assertTrue(line.contains("truncated"));
    }

    @Test
    void recordSampleFailsSoftOnWriteError() throws Exception {
        UawAutolearnProperties props = props();
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        props.getAgentHandoff().setRejectedPath(blocker.resolve("rejected.jsonl").toString());
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);

        writer.recordSample(
                "session-raw-123",
                "uaw-train",
                "question",
                "answer",
                "gemma4:26b",
                3,
                metadata(rejectedValidation(), true),
                false);

        assertEquals("sample_write_failed", TraceStore.get("uaw.agent.handoff.status"));
        String error = String.valueOf(TraceStore.get("uaw.agent.handoff.error"));
        assertFalse(error.isBlank());
        assertTrue(error.startsWith("hash:"), error);
        assertFalse(error.contains("FileAlreadyExistsException"));
        assertFalse(error.contains("java.nio.file"));
    }

    @Test
    void manifestSummaryHashesFileStatErrors() throws Exception {
        UawAutolearnProperties props = props();
        Files.createDirectories(tempDir.resolve("handoff/accepted.jsonl"));
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);

        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) writer.manifestSummary().get("files");
        @SuppressWarnings("unchecked")
        Map<String, Object> accepted = (Map<String, Object>) files.get("accepted");
        String error = String.valueOf(accepted.get("error"));

        assertFalse(accepted.containsKey("fileName"));
        assertTrue(accepted.containsKey("error"));
        assertTrue(error.startsWith("hash:"), error);
        assertFalse(error.contains(tempDir.toString()));
        assertFalse(error.contains("accepted.jsonl"));
    }

    @Test
    void recordCycleWritesHashOnlyDatasetFileDiagnostics() throws Exception {
        UawAutolearnProperties props = props();
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        String datasetPath = tempDir.resolve("private").resolve("train_rag.jsonl").toString();
        AutoLearnCycleResult result = new AutoLearnCycleResult(
                3,
                1,
                false,
                datasetPath,
                0.67d,
                0.05d,
                false,
                "provider_disabled",
                "BLOCK_RETRAIN");
        UawAutolearnQualityTracker.CycleDiagnostics cycle =
                new UawAutolearnQualityTracker.CycleDiagnostics(
                        3,
                        0.67d,
                        0.40d,
                        0.72d,
                        0.10d,
                        0.05d,
                        0.35d,
                        false,
                        "provider_disabled",
                        "BLOCK_RETRAIN",
                        0.33d,
                        Map.of("provider_disabled", 2),
                        java.util.List.of("error_rate_threshold"));

        writer.recordCycle("cycle-session-raw", datasetPath, result, cycle);

        String cycleLine = Files.readString(tempDir.resolve("handoff/cycles.jsonl"), StandardCharsets.UTF_8).trim();
        JsonNode cycleNode = objectMapper.readTree(cycleLine);
        assertFalse(cycleNode.has("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag.jsonl"), cycleNode.path("datasetFileHash").asText());
        assertEquals("train_rag.jsonl".length(), cycleNode.path("datasetFileLength").asInt());
        assertEquals(SafeRedactor.hashValue(datasetPath), cycleNode.path("datasetPathHash").asText());
        assertFalse(cycleLine.contains(datasetPath));
        assertFalse(cycleLine.contains("cycle-session-raw"));

        JsonNode latestCycle = objectMapper.readTree(
                Files.readString(tempDir.resolve("handoff/manifest.json"), StandardCharsets.UTF_8))
                .path("latestCycle");
        assertFalse(latestCycle.has("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag.jsonl"), latestCycle.path("datasetFileHash").asText());
        assertEquals("train_rag.jsonl".length(), latestCycle.path("datasetFileLength").asInt());
        assertEquals(SafeRedactor.hashValue(datasetPath), latestCycle.path("datasetPathHash").asText());
    }

    private UawAutolearnProperties props() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getAgentHandoff().setRootPath(tempDir.resolve("handoff").toString());
        props.getAgentHandoff().setAcceptedPath(tempDir.resolve("handoff/accepted.jsonl").toString());
        props.getAgentHandoff().setRejectedPath(tempDir.resolve("handoff/rejected.jsonl").toString());
        props.getAgentHandoff().setCyclePath(tempDir.resolve("handoff/cycles.jsonl").toString());
        props.getAgentHandoff().setManifestPath(tempDir.resolve("handoff/manifest.json").toString());
        return props;
    }

    private static UawDatasetWriter.TrainingMetadata metadata(LearningSampleValidationMetadata validation,
                                                             boolean finalGate) {
        return new UawDatasetWriter.TrainingMetadata(
                "uaw_autolearn",
                "local",
                "",
                3,
                finalGate,
                0.90d,
                validation);
    }

    private static LearningSampleValidationMetadata rejectedValidation() {
        return new LearningSampleValidationMetadata(
                "causal",
                java.util.List.of("BQ", "ER", "RC"),
                1.0d,
                0.0d,
                0.0d,
                0.0d,
                new LearningSampleValidationMetadata.Requery(true, false),
                0.0d,
                0.0d,
                0.30d,
                java.util.List.of("sample_score_below_threshold"),
                java.util.List.of("sample_score_min"));
    }
}
