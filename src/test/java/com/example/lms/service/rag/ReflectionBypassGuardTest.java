package com.example.lms.service.rag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionBypassGuardTest {

    private static final List<String> SCAN_ROOTS = List.of(
            "main/java/com/example/lms/service/rag",
            "main/java/com/example/lms/transform",
            "main/java/com/example/lms/service/ChatWorkflow.java",
            "main/java/com/abandonware/patch/config"
    );

    private static final List<String> ALLOWED_FILES = List.of(
            "main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java"
    );

    private static final Pattern PROHIBITED = Pattern.compile(
            "\\bClass\\.forName\\s*\\(|\\.getMethod\\s*\\(|\\.getDeclaredMethod\\s*\\(|\\.invoke\\s*\\(");

    @Test
    void ragRuntimeDoesNotUseReflectionBypassApisOutsideAllowlist() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String root : SCAN_ROOTS) {
            Path p = Path.of(root);
            if (!Files.exists(p)) {
                continue;
            }
            if (Files.isRegularFile(p)) {
                inspect(p, violations);
                continue;
            }
            try (Stream<Path> paths = Files.walk(p)) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> inspect(path, violations));
            }
        }

        assertTrue(violations.isEmpty(), "Reflection bypass API usage found: " + violations);
    }

    private static void inspect(Path path, List<String> violations) {
        String normalized = path.toString().replace('\\', '/');
        if (ALLOWED_FILES.contains(normalized)) {
            return;
        }
        try {
            String code = stripComments(Files.readString(path));
            if (PROHIBITED.matcher(code).find()) {
                violations.add(normalized);
            }
        } catch (IOException e) {
            violations.add(normalized + ":read_failed:" + e.getClass().getSimpleName());
        }
    }

    private static String stripComments(String input) {
        String withoutBlocks = input.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlocks.replaceAll("(?m)//.*$", "");
    }
}
