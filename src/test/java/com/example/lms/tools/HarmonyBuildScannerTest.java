package com.example.lms.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarmonyBuildScannerTest {

    @TempDir
    Path root;

    @Test
    void reportsHarmonyGreenGateTraceTestsAndSecretCounts() throws Exception {
        write("main/java/com/example/lms/HarmonyRuntime.java", """
                package com.example.lms;
                class HarmonyRuntime {
                    static final String LLM_PRIMARY_PORT_KEY = "llm.primary.port";
                    void trace() {
                        TraceStore.put("boosterMode.active", "EXTREMEZ");
                        TraceStore.put("boosterMode.excludedModes", "OVERDRIVE,HYPERNOVA");
                        TraceStore.put("boosterMode.exclusionReason", "priority");
                        TraceStore.put("retrievalOrder.lastSetBy", "MoE");
                        TraceStore.put("extremeZ.activated", true);
                        TraceStore.put("extremeZ.triggerReasons", "lowRecall");
                        TraceStore.put("extremeZ.subQueryCount", 12);
                        TraceStore.put("extremeZ.parallelBranchCount", 24);
                        TraceStore.put("extremeZ.mergedDocCount", 12);
                        TraceStore.put("extremeZ.rrfApplied", true);
                        TraceStore.put("extremeZ.timeoutMs", 1500L);
                        TraceStore.put("extremeZ.bypassReason", "");
                        TraceStore.put("extremeZ.cancelShieldWrapped", true);
                        TraceStore.put("extremeZ.timeBudgetConsumedMs", 17L);
                        TraceStore.put("hypernova.cvarPhi", 0.618d);
                        TraceStore.put("hypernova.dppApplied", true);
                        TraceStore.put("hypernova.sourceScoreScaleMismatchCount", 0);
                        TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
                        TraceStore.put("moe.evolverPlateRegistered", true);
                        TraceStore.put("cfvm.boltzmannTemp", 1.0d);
                        TraceStore.put("cfvm.tempSource", "RawMatrixBuffer");
                        TraceStore.put("hypernova.whitening.applied", true);
                        TraceStore.put("hypernova.whitening.method", "LowRankZCA");
                        TraceStore.put("hypernova.whitening.provider", "LowRankWhiteningTransform");
                        TraceStore.put("overdrive.stagesApplied", 1);
                        TraceStore.put("overdrive.exactPhraseProbeUsed", true);
                        TraceStore.put("embed.sliceMethod", "MRL_PREFIX");
                        TraceStore.put("embed.normalizeApplied", true);
                        TraceStore.put("cihRag.iqrIterations", 1);
                        TraceStore.put("cihRag.mlaBreadcrumbCount", 2);
                        TraceStore.put("cihRag.routedModel", "gemma4");
                        TraceStore.put("cihRag.ucb1Reward", 1);
                        TraceStore.put("moe.selectedPlate", "AP3_VEC_DENSE");
                        TraceStore.put("moe.evolver.abSlot", "experiment");
                        TraceStore.put("moe.criticExhausted", false);
                        TraceStore.put("overdrive.trigger.sparse", true);
                        TraceStore.put("overdrive.trigger.lowAuth", false);
                        TraceStore.put("overdrive.trigger.contradicted", false);
                        TraceStore.put("overdrive.blackbox.available", true);
                        TraceStore.put("cfvm.triggered", true);
                        TraceStore.put("chain.steps.planned", 4);
                        TraceStore.put("llm.versionPurity", "PASS");
                        TraceStore.put(LLM_PRIMARY_PORT_KEY, 11434);
                        TraceStore.put("strategy.conflict.overdriveDeferred", false);
                    }
                }
                """);
        write("main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java", """
                package com.example.lms.service.rag.rerank;
                class DppDiversityReranker {}
                """);
        write("main/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuser.java", """
                package com.example.lms.service.rag.fusion;
                class TailWeightedPowerMeanFuser {}
                """);
        write("main/java/com/example/lms/cfvm/CfvmRawTileBuilder.java", """
                package com.example.lms.cfvm;
                class CfvmRawTileBuilder {
                    void build() {
                        TraceStore.put("cfvm.rawTileBuilderDisabled", false);
                        TraceStore.put("cfvm.rawTileId", "id");
                    }
                }
                """);
        writeTest("OverdriveGuardTest");
        writeTest("RawMatrixBufferTest");
        writeTest("RgbStrategySelectorTraceTest");
        writeTest("OllamaEmbeddingModelTest");
        writeTest("ExtremeZTriggerTest");
        writeTest("NovaNextFusionServiceTest");
        writeTest("AttachmentContextHandlerTraceTest");
        writeTest("VersionPurityHealthIndicatorTest");

        Path report = root.resolve("verification/harmony-build-report.txt");

        HarmonyBuildScanner.main(new String[]{"--root", root.toString(), "--output", report.toString()});

        String text = Files.readString(report);
        assertTrue(text.contains("Dynamic RAG Harmony Build Report"));
        assertTrue(text.contains("[harmony][trace] boosterMode.active=FOUND"));
        assertTrue(text.contains("[harmony][trace] extremeZ.activated=FOUND"));
        assertTrue(text.contains("[harmony][trace] extremeZ.subQueryCount=FOUND"));
        assertTrue(text.contains("[harmony][trace] hypernova.whitening.applied=FOUND"));
        assertTrue(text.contains("[harmony][trace] cfvm.boltzmannTemp=FOUND"));
        assertTrue(text.contains("[harmony][trace] overdrive.stagesApplied=FOUND"));
        assertTrue(text.contains("[harmony][trace] embed.sliceMethod=FOUND"));
        assertTrue(text.contains("[harmony][trace] cihRag.iqrIterations=FOUND"));
        assertTrue(text.contains("[harmony][trace] cihRag.routedModel=FOUND"));
        assertTrue(text.contains("[harmony][trace] cihRag.ucb1Reward=FOUND"));
        assertTrue(text.contains("[harmony][trace] moe.selectedPlate=FOUND"));
        assertTrue(text.contains("[harmony][trace] moe.evolver.abSlot=FOUND"));
        assertTrue(text.contains("[harmony][trace] overdrive.trigger.sparse=FOUND"));
        assertTrue(text.contains("[harmony][trace] overdrive.blackbox.available=FOUND"));
        assertTrue(text.contains("[harmony][trace] cfvm.triggered=FOUND"));
        assertTrue(text.contains("[harmony][trace] chain.steps.planned=FOUND"));
        assertTrue(text.contains("[harmony][trace] llm.versionPurity=FOUND"));
        assertTrue(text.contains("[harmony][trace] llm.primary.port=FOUND"));
        assertTrue(text.contains("[harmony][trace] strategy.conflict.overdriveDeferred=FOUND"));
        assertTrue(text.contains("[harmony][tests] S01=FOUND"));
        assertTrue(text.contains("[harmony][HB-03] DONE"));
        assertTrue(text.contains("[harmony][security] secretPatternHits=0"));
    }

    @Test
    void reportsMissingCoverageAndSecretCountsWithoutLeakingSecretValues() throws Exception {
        String secret = "sk-" + "synthetic-harmony-token-0000";
        write("main/java/com/example/lms/Leaky.java", """
                package com.example.lms;
                class Leaky {
                    String secret = "%s";
                }
                """.formatted(secret));

        Path report = root.resolve("verification/harmony-build-report.txt");

        HarmonyBuildScanner.main(new String[]{"--root", root.toString(), "--output", report.toString()});

        String text = Files.readString(report);
        assertTrue(text.contains("[harmony][trace] boosterMode.active=MISSING"));
        assertTrue(text.contains("[harmony][HB-03] OPEN"));
        assertTrue(text.contains("[harmony][security] secretPatternHits=1"));
        assertFalse(text.contains(secret));
    }

    @Test
    void countsSupabaseApiKeyPrefixesWithoutLeakingValues() throws Exception {
        String secret = "sb_secret_" + "harmony0123456789";
        String publishable = "sb_publishable_" + "harmony0123456789";
        write("main/java/com/example/lms/SupabaseLeaky.java", """
                package com.example.lms;
                class SupabaseLeaky {
                    String secret = "%s";
                    String publishable = "%s";
                }
                """.formatted(secret, publishable));

        Path report = root.resolve("verification/harmony-build-report.txt");

        HarmonyBuildScanner.main(new String[]{"--root", root.toString(), "--output", report.toString()});

        String text = Files.readString(report);
        assertTrue(text.contains("[harmony][security] secretPatternHits=2"));
        assertFalse(text.contains(secret));
        assertFalse(text.contains(publishable));
    }

    @Test
    void rootGradleExposesHarmonyScoreReportTask() throws Exception {
        String build = Files.readString(Path.of("build.gradle.kts"));

        assertTrue(build.contains("tasks.register<JavaExec>(\"harmonyScoreReport\")"));
        assertTrue(build.contains("com.example.lms.tools.HarmonyBuildScanner"));
        assertTrue(build.contains("harmonyScoreOutput"));
    }

    private void writeTest(String className) throws Exception {
        write("src/test/java/com/example/lms/" + className + ".java", """
                package com.example.lms;
                class %s {}
                """.formatted(className));
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
