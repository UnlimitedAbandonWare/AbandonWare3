package com.example.lms.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScoringRunnerTest {

    @TempDir
    Path root;

    @Test
    void scoresActiveDesktopRootOnHundredPointSourceAnalysisScale() throws Exception {
        write("build.gradle.kts", """
                val langchain4jVersion = "1.0.1"
                implementation("dev.langchain4j:langchain4j:$langchain4jVersion")
                tasks.register("checkLangchain4jVersionPurity")
                sourceSets {
                    main {
                        java { srcDirs("main/java") }
                        resources { srcDirs("main/resources") }
                    }
                }
                """);
        write("main/java/com/example/lms/service/rag/learn/CfvmKAllocationTuner.java", """
                package com.example.lms.service.rag.learn;
                class CfvmKAllocationTuner {
                    double boltzmannSoftmax;
                    int[][] nineTileMatrix;
                    String lissajousTrajectory;
                    String rawTileVectorStorePush;
                }
                """);
        write("main/java/com/example/lms/artplate/ArtPlateEvolver.java", """
                package com.example.lms.artplate;
                class ArtPlateEvolver {
                    String AP1, AP2, AP3, AP4, AP5, AP6, AP7, AP8, AP9;
                    String emergentPlate;
                    String abFivePercent;
                }
                """);
        write("main/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuser.java", """
                package com.example.lms.service.rag.fusion;
                class TailWeightedPowerMeanFuser {
                    double tailWeightedPowerMean;
                }
                """);
        write("main/java/com/nova/protocol/fusion/CvarAggregator.java", """
                package com.nova.protocol.fusion;
                class CvarAggregator {
                    double cvarAlpha;
                }
                """);
        write("main/java/com/nova/protocol/alloc/SimpleRiskKAllocator.java", """
                package com.nova.protocol.alloc;
                class SimpleRiskKAllocator {
                    double riskAdjustedSoftmax;
                }
                """);
        write("main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java", """
                package com.example.lms.service.rag.rerank;
                class DppDiversityReranker {
                    double determinantalKernelSelection;
                    double determinant;
                }
                """);
        write("main/java/com/example/lms/service/rag/mp/LowRankWhiteningStats.java", """
                package com.example.lms.service.rag.mp;
                class LowRankWhiteningStats {
                    double zcaWhitening;
                    double invSqrtEigenvalue;
                }
                """);
        write("main/java/com/example/lms/service/guard/PIISanitizer.java", """
                package com.example.lms.service.guard;
                class PIISanitizer {
                    String email = "[redacted-email]";
                    String phone = "[redacted-phone]";
                    String token = "[redacted-token]";
                    String api = "api_key=[redacted]";
                }
                """);
        write("main/java/com/example/lms/service/guard/CitationGate.java", """
                package com.example.lms.service.guard;
                class CitationGate {
                    String canonicalPipelineNamespace;
                }
                """);
        write("main/java/com/example/lms/nova/gate/CitationGate.java", """
                package com.example.lms.nova.gate;
                class CitationGateAlias {
                    String deprecatedAliases;
                }
                """);
        write("main/java/com/example/lms/prompt/StandardPromptBuilder.java", """
                package com.example.lms.prompt;
                class StandardPromptBuilder {
                    String buildPrompt = "PromptBuilder.build(PromptContext)";
                    String trace = "TraceStore.put";
                }
                """);

        Path report = root.resolve("verification/source-score.txt");

        ScoringRunner.main(new String[]{"--root", root.toString(), "--output", report.toString()});

        String text = Files.readString(report);
        assertTrue(text.contains("Dynamic RAG Source Score"));
        assertTrue(text.contains("Total Score: 100 / 100"));
        assertTrue(text.contains("[score][silent-catch] exactEmptyCatchMatches=0"));
        assertTrue(text.contains("[score][structure] largeActiveSourceFiles=0 thresholdLines=2000"));
    }

    @Test
    void rootGradleExposesDesktopSourceScoreReportTask() throws Exception {
        String build = Files.readString(Path.of("build.gradle.kts"));

        assertTrue(build.contains("tasks.register<JavaExec>(\"sourceScoreReport\")"));
        assertTrue(build.contains("com.example.lms.tools.ScoringRunner"));
        assertTrue(build.contains("sourceScoreOutput"));
    }

    @Test
    void reportsLargeActiveSourceFileHotspotsWithLineCounts() throws Exception {
        StringBuilder giant = new StringBuilder("package com.example.lms.big;\nclass Giant {\n");
        for (int i = 0; i < 2_001; i++) {
            giant.append("    void m").append(i).append("() {}\n");
        }
        giant.append("}\n");
        write("main/java/com/example/lms/big/Giant.java", giant.toString());

        Path report = root.resolve("verification/source-score-large.txt");

        ScoringRunner.main(new String[]{"--root", root.toString(), "--output", report.toString()});

        String text = Files.readString(report);
        assertTrue(text.contains("[score][structure] largeActiveSourceFiles=1 thresholdLines=2000"));
        assertTrue(text.contains("[score][structure][large-file] main/java/com/example/lms/big/Giant.java lines=2004"));
    }

    @Test
    void readFailuresIncludeTheSourcePath() throws Exception {
        Path badFile = root.resolve("main/java/com/example/lms/bad/Bad.java");
        Files.createDirectories(badFile.getParent());
        Files.write(badFile, new byte[] {(byte) 0x80});

        Exception failure = assertThrows(Exception.class,
                () -> ScoringRunner.main(new String[]{"--root", root.toString()}));

        assertTrue(failure.getMessage().contains("main/java/com/example/lms/bad/Bad.java"),
                failure.getMessage());
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
