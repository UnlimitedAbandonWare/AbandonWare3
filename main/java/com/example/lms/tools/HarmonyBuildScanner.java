package com.example.lms.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static Harmony gate scanner for the active Desktop source root.
 *
 * <p>The scanner never executes providers or runtime calls. It checks source,
 * tests, and resources for the HB-01..HB-12 evidence required by the Harmony
 * directive and prints count-only diagnostics.</p>
 */
public class HarmonyBuildScanner {

    private static final Pattern EMPTY_CATCH = Pattern.compile(
            "catch[ \\t]*\\([^\\r\\n)]*(Exception|Throwable|RuntimeException|IOException|"
                    + "IllegalStateException)[^\\r\\n)]*\\)[ \\t]*\\{[ \\t]*(?://[^\\r\\n]*)?[ \\t]*\\}",
            Pattern.MULTILINE);
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|"
                    + "pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?(?:final\\s+|abstract\\s+)?(?:class|interface|enum|record)\\s+([A-Za-z0-9_]+)");

    private static final List<String> TRACE_KEYS = List.of(
            "boosterMode.active",
            "boosterMode.excludedModes",
            "boosterMode.exclusionReason",
            "retrievalOrder.lastSetBy",
            "extremeZ.activated",
            "extremeZ.triggerReasons",
            "extremeZ.subQueryCount",
            "extremeZ.parallelBranchCount",
            "extremeZ.mergedDocCount",
            "extremeZ.rrfApplied",
            "extremeZ.timeoutMs",
            "extremeZ.bypassReason",
            "extremeZ.cancelShieldWrapped",
            "extremeZ.timeBudgetConsumedMs",
            "hypernova.cvarPhi",
            "hypernova.dppApplied",
            "hypernova.sourceScoreScaleMismatchCount",
            "hypernova.twpmP",
            "hypernova.cvarAlpha",
            "hypernova.cvarFusedScore",
            "hypernova.riskKAlloc",
            "hypernova.clampApplied",
            "hypernova.finalGatePassed",
            "cihRag.breadcrumb.queryRedacted",
            "cihRag.iqrIterations",
            "cihRag.activeFileCount",
            "cihRag.skippedFileCount",
            "cihRag.mlaBreadcrumbCount",
            "cihRag.onnxRerankApplied",
            "cihRag.routedModel",
            "cihRag.ucb1Reward",
            "moe.evolverPlateRegistered",
            "moe.evolverCandidatePlateId",
            "moe.abSlot",
            "moe.evolver.abSlot",
            "moe.selectedPlate",
            "moe.signalVector",
            "moe.criticAttempts",
            "moe.criticExhausted",
            "moe.criticLastReason",
            "overdrive.triggerReasons",
            "overdrive.trigger.sparse",
            "overdrive.trigger.lowAuth",
            "overdrive.trigger.contradicted",
            "overdrive.blackbox.available",
            "overdrive.finalCandidateCount",
            "overdrive.stagesApplied",
            "overdrive.exactPhraseProbeUsed",
            "overdrive.bypassReason",
            "embed.sourceDim",
            "embed.targetDim",
            "embed.sliceMethod",
            "embed.normalizeApplied",
            "embed.sliceReason",
            "cfvm.activeTile",
            "cfvm.triggered",
            "cfvm.jb.score",
            "cfvm.cb.score",
            "cfvm.boltzmannWeight",
            "cfvm.rawTileId",
            "cfvm.retrievalOrderAdjusted",
            "cfvm.recoveryPath",
            "cfvm.boltzmannTemp",
            "cfvm.tempSource",
            "cfvm.tempAnnealApplied",
            "chain.steps.planned",
            "llm.versionPurity",
            "llm.primary.port",
            "strategy.conflict.overdriveDeferred",
            "hypernova.whitening.applied",
            "hypernova.whitening.method",
            "hypernova.whitening.provider"
    );

    private static final List<TestGroup> TEST_GROUPS = List.of(
            new TestGroup("S01", "Overdrive", List.of("Overdrive", "DynamicContextCompress")),
            new TestGroup("S02", "CFVM", List.of("Cfvm", "RawMatrix", "RawSlot", "RetrievalOrder")),
            new TestGroup("S03", "MoE", List.of("NineArtPlateGate", "RgbStrategySelector",
                    "MoeCandidateRouter", "CriticNode", "ArtPlate")),
            new TestGroup("S04", "Matryoshka", List.of("Embedding", "Matryoshka", "OllamaEmbedding")),
            new TestGroup("S05", "ExtremeZ", List.of("ExtremeZ", "QueryBurst", "CancelShield")),
            new TestGroup("S06", "HYPERNOVA", List.of("TailWeighted", "CvarAgg", "RiskK",
                    "DppDiversity", "Hypernova")),
            new TestGroup("S07", "CIH-RAG", List.of("AttachmentContext", "LlmRouter", "OnnxCrossEncoder")),
            new TestGroup("S08", "Version Purity", List.of("ProviderGuard", "VersionPurity", "LlmConfig"))
    );

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        HarmonyReport report = scan(parsed.root());
        Path parent = parsed.output().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String rendered = report.render();
        Files.writeString(parsed.output(), rendered);
        System.out.println(rendered);
    }

    static HarmonyReport scan(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        String mainJava = readTree(normalizedRoot.resolve("main/java"), ".java")
                + readTree(normalizedRoot.resolve("app/src/main/java_clean"), ".java");
        String resources = readTree(normalizedRoot.resolve("main/resources"), ".yml", ".yaml", ".properties")
                + readTree(normalizedRoot.resolve("app/src/main/resources"), ".yml", ".yaml", ".properties");
        List<String> testPaths = collectRelativePaths(normalizedRoot, normalizedRoot.resolve("src/test/java"));
        String lowerMainJava = mainJava.toLowerCase(Locale.ROOT);
        String secretScanText = mainJava + "\n" + resources;

        Map<String, Boolean> traceCoverage = new LinkedHashMap<>();
        for (String key : TRACE_KEYS) {
            traceCoverage.put(key, containsTraceKey(mainJava, key));
        }

        Map<TestGroup, Boolean> testCoverage = new LinkedHashMap<>();
        for (TestGroup group : TEST_GROUPS) {
            testCoverage.put(group, testPaths.stream().anyMatch(group::matches));
        }

        int exactEmptyCatchMatches = countMatches(EMPTY_CATCH, mainJava);
        int secretPatternHits = countMatches(SECRET_PATTERN, secretScanText);
        int duplicateFqcnCount = duplicateFqcnCount(normalizedRoot);

        List<HarmonyBreak> breaks = List.of(
                hb("HB-01", "silent catch contamination",
                        exactEmptyCatchMatches <= 45 && lowerMainJava.contains("failsoft")),
                hb("HB-02", "retrieval order authority",
                        traceCoverage.get("retrievalOrder.lastSetBy")
                                && containsAll(mainJava, "class RetrievalOrderService")),
                hb("HB-03", "booster mutual exclusion",
                        traceCoverage.get("boosterMode.active")
                                && traceCoverage.get("boosterMode.excludedModes")
                                && traceCoverage.get("boosterMode.exclusionReason")),
                hb("HB-04", "DPP in HYPERNOVA",
                        traceCoverage.get("hypernova.dppApplied")
                                && containsAll(mainJava, "DppDiversityReranker")),
                hb("HB-05", "TWPM canonical path",
                        containsTraceKey(mainJava, "hypernova.twpmP")
                                && containsAll(mainJava, "TailWeightedPowerMeanFuser")),
                hb("HB-06", "source score scale normalization",
                        traceCoverage.get("hypernova.sourceScoreScaleMismatchCount")
                                || containsTraceKey(mainJava, "fusion.scoreNormalized")),
                hb("HB-07", "CancelShield interrupt hygiene",
                        traceCoverage.get("extremeZ.cancelShieldWrapped")
                                && traceCoverage.get("extremeZ.timeBudgetConsumedMs")
                                && mainJava.contains("cancel(false)")),
                hb("HB-08", "CFVM Boltzmann temperature ownership",
                        traceCoverage.get("cfvm.boltzmannTemp") && traceCoverage.get("cfvm.tempSource")),
                hb("HB-09", "CFVM raw tile builder enabled path",
                        containsAny(mainJava, "cfvm.rawTileId", "cfvm.rawTile.enabled",
                                "cfvm.rawTileBuilderDisabled")),
                hb("HB-10", "MoE evolver PromptBuilder boundary",
                        traceCoverage.get("moe.evolverPlateRegistered")
                                && !lowerMainJava.contains("setprompttemplate(\"")),
                hb("HB-11", "TimeBudgetGuard trace",
                        traceCoverage.get("extremeZ.timeBudgetConsumedMs")
                                || containsTraceKey(mainJava, "timeBudget.firstExhaustedStage")),
                hb("HB-12", "ZCA whitening provider ownership",
                        traceCoverage.get("hypernova.whitening.provider"))
        );

        return new HarmonyReport(
                normalizedRoot,
                traceCoverage,
                testCoverage,
                breaks,
                exactEmptyCatchMatches,
                duplicateFqcnCount,
                secretPatternHits);
    }

    private static HarmonyBreak hb(String id, String label, boolean done) {
        return new HarmonyBreak(id, label, done ? "DONE" : "OPEN");
    }

    private static boolean containsTraceKey(String content, String key) {
        return content.contains("\"" + key + "\"") || content.contains("'" + key + "'");
    }

    private static boolean containsAll(String content, String... needles) {
        for (String needle : needles) {
            if (!content.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAny(String content, String... needles) {
        for (String needle : needles) {
            if (content.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String readTree(Path root, String... suffixes) throws IOException {
        if (!Files.isDirectory(root)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> hasSuffix(path, suffixes))
                    .toList()) {
                out.append(Files.readString(path)).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean hasSuffix(Path path, String... suffixes) {
        String value = path.toString().toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (value.endsWith(suffix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> collectRelativePaths(Path sourceRoot, Path root) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                out.add(sourceRoot.relativize(path).toString().replace('\\', '/'));
            }
        }
        return out;
    }

    private static int duplicateFqcnCount(Path root) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Path javaRoot : List.of(root.resolve("main/java"), root.resolve("app/src/main/java_clean"))) {
            if (!Files.isDirectory(javaRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(javaRoot)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .toList()) {
                    String text = Files.readString(path);
                    Matcher pkg = PACKAGE_PATTERN.matcher(text);
                    Matcher type = TYPE_PATTERN.matcher(text);
                    if (pkg.find() && type.find()) {
                        String fqcn = pkg.group(1) + "." + type.group(1);
                        counts.put(fqcn, counts.getOrDefault(fqcn, 0) + 1);
                    }
                }
            }
        }
        int duplicates = 0;
        for (int count : counts.values()) {
            if (count > 1) {
                duplicates++;
            }
        }
        return duplicates;
    }

    record Args(Path root, Path output) {
        static Args parse(String[] args) {
            Path root = Paths.get("").toAbsolutePath().normalize();
            Path output = root.resolve("verification/harmony-build-report.txt");
            for (int i = 0; args != null && i < args.length; i++) {
                if ("--root".equals(args[i]) && i + 1 < args.length) {
                    root = Paths.get(args[++i]).toAbsolutePath().normalize();
                    if (!output.isAbsolute() || output.startsWith(Paths.get("").toAbsolutePath().normalize())) {
                        output = root.resolve("verification/harmony-build-report.txt");
                    }
                } else if ("--output".equals(args[i]) && i + 1 < args.length) {
                    output = Paths.get(args[++i]).toAbsolutePath().normalize();
                }
            }
            return new Args(root, output);
        }
    }

    record TestGroup(String id, String label, List<String> patterns) {
        boolean matches(String path) {
            String lowerPath = path.toLowerCase(Locale.ROOT);
            for (String pattern : patterns) {
                if (lowerPath.contains(pattern.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }

    record HarmonyBreak(String id, String label, String status) {
    }

    record HarmonyReport(Path root, Map<String, Boolean> traceCoverage,
                         Map<TestGroup, Boolean> testCoverage,
                         List<HarmonyBreak> breaks,
                         int exactEmptyCatchMatches,
                         int duplicateFqcnCount,
                         int secretPatternHits) {
        String render() {
            StringBuilder report = new StringBuilder();
            report.append("=== Dynamic RAG Harmony Build Report ===\n");
            report.append("root=").append(root).append('\n');
            report.append("[harmony][sourceset] activeRoots=main/java,main/resources,src/test/java,app/src/main/java_clean,app/src/main/resources\n");
            for (Map.Entry<String, Boolean> entry : traceCoverage.entrySet()) {
                report.append("[harmony][trace] ")
                        .append(entry.getKey())
                        .append('=')
                        .append(entry.getValue() ? "FOUND" : "MISSING")
                        .append('\n');
            }
            for (Map.Entry<TestGroup, Boolean> entry : testCoverage.entrySet()) {
                report.append("[harmony][tests] ")
                        .append(entry.getKey().id())
                        .append('=')
                        .append(entry.getValue() ? "FOUND" : "MISSING")
                        .append(" label=")
                        .append(entry.getKey().label())
                        .append('\n');
            }
            for (HarmonyBreak item : breaks) {
                report.append("[harmony][")
                        .append(item.id())
                        .append("] ")
                        .append(item.status())
                        .append(" label=")
                        .append(item.label())
                        .append('\n');
            }
            report.append("[harmony][catch] exactEmptyCatchMatches=")
                    .append(exactEmptyCatchMatches)
                    .append('\n');
            report.append("[harmony][duplicate-fqcn] duplicateCount=")
                    .append(duplicateFqcnCount)
                    .append('\n');
            report.append("[harmony][security] secretPatternHits=")
                    .append(secretPatternHits)
                    .append('\n');
            return report.toString();
        }
    }
}
