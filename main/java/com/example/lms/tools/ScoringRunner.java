package com.example.lms.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static score-analysis harness for the active Desktop source root.
 *
 * <p>The runner intentionally does not execute providers or retrieval
 * pipelines. It verifies the source families called out by
 * {@code source_score_analysis.md} and writes a deterministic report.</p>
 */
public class ScoringRunner {

    private static final Pattern EMPTY_CATCH = Pattern.compile(
            "catch[ \\t]*\\([^\\r\\n)]*(Exception|Throwable|RuntimeException|IOException|NumberFormatException|"
                    + "IllegalStateException|DateTimeParseException)[^\\r\\n)]*\\)[ \\t]*\\{[ \\t]*(?://[^\\r\\n]*)?[ \\t]*\\}",
            Pattern.MULTILINE);

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        ScoreReport report = score(parsed.root());
        Path parent = parsed.output().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String rendered = report.render();
        Files.writeString(parsed.output(), rendered);
        System.out.println(rendered);
    }

    static ScoreReport score(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        String build = readFile(normalizedRoot.resolve("build.gradle.kts"))
                + readFile(normalizedRoot.resolve("build.gradle"));
        String mainJava = readTree(normalizedRoot.resolve("main/java"));
        String testJava = readTree(normalizedRoot.resolve("src/test/java"));
        String cfvm = readFile(normalizedRoot.resolve(
                "main/java/com/example/lms/service/rag/learn/CfvmKAllocationTuner.java"));
        String artPlate = readTree(normalizedRoot.resolve("main/java/com/example/lms/artplate"));
        String hypernova = readFile(normalizedRoot.resolve(
                "main/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuser.java"))
                + readFile(normalizedRoot.resolve("main/java/com/nova/protocol/fusion/CvarAggregator.java"))
                + readFile(normalizedRoot.resolve("main/java/com/nova/protocol/alloc/SimpleRiskKAllocator.java"))
                + readFile(normalizedRoot.resolve(
                "main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java"))
                + readFile(normalizedRoot.resolve(
                "main/java/com/example/lms/service/rag/mp/LowRankWhiteningStats.java"));
        String pii = readFile(normalizedRoot.resolve(
                "main/java/com/example/lms/service/guard/PIISanitizer.java"));
        String citation = readFile(normalizedRoot.resolve(
                "main/java/com/example/lms/service/guard/CitationGate.java"))
                + readFile(normalizedRoot.resolve("main/java/com/example/lms/nova/gate/CitationGate.java"))
                + testJava;
        String lowerJava = mainJava.toLowerCase(Locale.ROOT);
        String lowerCfvm = cfvm.toLowerCase(Locale.ROOT);
        String lowerHypernova = hypernova.toLowerCase(Locale.ROOT);
        int exactEmptyCatchMatches = countMatches(EMPTY_CATCH, mainJava);
        List<LargeSourceFile> largeActiveSourceFiles = new ArrayList<>();
        largeActiveSourceFiles.addAll(collectLargeJavaFiles(
                normalizedRoot, normalizedRoot.resolve("main/java"), 2_000));
        largeActiveSourceFiles.addAll(collectLargeJavaFiles(
                normalizedRoot, normalizedRoot.resolve("app/src/main/java_clean"), 2_000));
        largeActiveSourceFiles.sort((a, b) -> {
            int byLines = Integer.compare(b.lines(), a.lines());
            return byLines != 0 ? byLines : a.path().compareTo(b.path());
        });

        List<Check> checks = new ArrayList<>();
        checks.add(new Check(
                "active-root",
                "Desktop active sourceSet and LangChain4j purity gates",
                10,
                Files.isDirectory(normalizedRoot.resolve("main/java"))
                        && containsAll(build, "checkLangchain4jVersionPurity", "main/java", "main/resources", "1.0.1")));
        checks.add(new Check(
                "cfvm",
                "CFVM Boltzmann 9-tile K allocation",
                15,
                containsAll(lowerCfvm, "cfvmkallocationtuner", "boltzmann", "tile")
                        && containsAny(lowerCfvm, "cfvm9", "nine")
                        && containsAny(lowerCfvm, "tracestore.put(\"cfvm.kalloc", "rawtile", "vector")));
        checks.add(new Check(
                "artplate",
                "MoE ArtPlate AP5/AP6/AP8 plus rollout/evolver path",
                10,
                containsAll(artPlate, "ArtPlateEvolver", "AP5", "AP6", "AP8")
                        && containsAny(artPlate, "abTest", "rolloutPercent", "abFivePercent")
                        && !artPlate.contains("Stub evolver")));
        checks.add(new Check(
                "hypernova",
                "HYPERNOVA TWPM/CVaR/RiskK/ZCA/DPP math components",
                20,
                containsAll(hypernova,
                        "TailWeightedPowerMeanFuser",
                        "CvarAggregator",
                        "SimpleRiskKAllocator",
                        "DppDiversityReranker",
                        "LowRankWhiteningStats")
                        && containsAll(lowerHypernova, "determinantal", "determinant", "zca", "whitening")
                        && !hypernova.contains("MMR-style approximation")
                        && !hypernova.contains("Do not assume it is a determinantal-kernel solver")));
        checks.add(new Check(
                "pii",
                "PIISanitizer masks common PII and credential fragments",
                10,
                containsAll(pii,
                        "[redacted-email]",
                        "[redacted-phone]",
                        "[redacted-token]")
                        && containsAny(pii,
                        "api_key=[redacted]",
                        "api[_-]?key|token|secret|password",
                        "$1=[redacted]")));
        checks.add(new Check(
                "citation-gate",
                "CitationGate duplicate simple-name ownership is explicit",
                10,
                containsAll(citation, "com.example.lms.service.guard", "class CitationGate")
                        && containsAny(citation,
                        "activeCitationGateSimpleNameDuplicatesHaveExplicitOwnership",
                        "@Deprecated(since",
                        "deprecatedAliases")));
        checks.add(new Check(
                "prompt-trace",
                "PromptBuilder boundary and TraceStore observability",
                10,
                containsAny(mainJava, "PromptBuilder.build(PromptContext)", "promptBuilder.build(")
                        && containsAny(mainJava, "TraceStore.put", "TraceStore.inc")));
        checks.add(new Check(
                "silent-catch",
                "Silent catch budget reduced below source-score baseline",
                silentCatchPoints(exactEmptyCatchMatches),
                15,
                exactEmptyCatchMatches <= 45));
        return new ScoreReport(normalizedRoot, checks, exactEmptyCatchMatches, largeActiveSourceFiles);
    }

    private static int silentCatchPoints(int exactEmptyCatchMatches) {
        if (exactEmptyCatchMatches <= 45) {
            return 15;
        }
        if (exactEmptyCatchMatches <= 50) {
            return 10;
        }
        if (exactEmptyCatchMatches <= 60) {
            return 5;
        }
        return 0;
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean containsAll(String content, String... patterns) {
        for (String pattern : patterns) {
            if (!content.contains(pattern)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAny(String content, String... patterns) {
        for (String pattern : patterns) {
            if (content.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String readTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                out.append(readFile(path)).append('\n');
            }
        }
        return out.toString();
    }

    private static String readFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IOException("source score read failed path=" + path.toString().replace('\\', '/'), ex);
        }
    }

    private static List<LargeSourceFile> collectLargeJavaFiles(
            Path sourceRoot, Path root, int thresholdLines) throws IOException {
        List<LargeSourceFile> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                int lines = Files.readAllLines(path).size();
                if (lines > thresholdLines) {
                    String relativePath = sourceRoot.relativize(path)
                            .toString()
                            .replace('\\', '/');
                    out.add(new LargeSourceFile(relativePath, lines));
                }
            }
        }
        return out;
    }

    record Args(Path root, Path output) {
        static Args parse(String[] args) {
            Path root = Paths.get("").toAbsolutePath().normalize();
            Path output = root.resolve("verification/source-score-report.txt");
            for (int i = 0; args != null && i < args.length; i++) {
                if ("--root".equals(args[i]) && i + 1 < args.length) {
                    root = Paths.get(args[++i]).toAbsolutePath().normalize();
                    if (!output.isAbsolute() || output.startsWith(Paths.get("").toAbsolutePath().normalize())) {
                        output = root.resolve("verification/source-score-report.txt");
                    }
                } else if ("--output".equals(args[i]) && i + 1 < args.length) {
                    output = Paths.get(args[++i]).toAbsolutePath().normalize();
                }
            }
            return new Args(root, output);
        }
    }

    record Check(String id, String label, int earned, int points, boolean ok) {
        Check(String id, String label, int points, boolean ok) {
            this(id, label, ok ? points : 0, points, ok);
        }
    }

    record LargeSourceFile(String path, int lines) {
    }

    record ScoreReport(Path root, List<Check> checks, int exactEmptyCatchMatches,
                       List<LargeSourceFile> largeActiveSourceFiles) {
        int total() {
            return checks.stream().mapToInt(Check::earned).sum();
        }

        String render() {
            StringBuilder report = new StringBuilder();
            report.append("=== Dynamic RAG Source Score ===\n");
            report.append("root=").append(root).append('\n');
            for (Check check : checks) {
                report.append("[score][").append(check.id()).append("] ")
                        .append(check.ok() ? "OK" : "MISSING")
                        .append(" (+").append(check.earned()).append('/').append(check.points()).append(") ")
                        .append(check.label()).append('\n');
            }
            report.append("[score][silent-catch] exactEmptyCatchMatches=")
                    .append(exactEmptyCatchMatches).append('\n');
            report.append("[score][structure] largeActiveSourceFiles=")
                    .append(largeActiveSourceFiles.size())
                    .append(" thresholdLines=2000").append('\n');
            for (LargeSourceFile largeFile : largeActiveSourceFiles.stream().limit(20).toList()) {
                report.append("[score][structure][large-file] ")
                        .append(largeFile.path())
                        .append(" lines=")
                        .append(largeFile.lines())
                        .append('\n');
            }
            report.append("\n=== Total Score: ").append(total()).append(" / 100 ===\n");
            return report.toString();
        }
    }
}
