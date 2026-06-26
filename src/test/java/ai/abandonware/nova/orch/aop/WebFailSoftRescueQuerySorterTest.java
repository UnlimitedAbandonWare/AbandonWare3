package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFailSoftRescueQuerySorterTest {

    @Test
    void rescueSortTraceCatchUsesSuppressionBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/WebFailSoftRescueQuerySorter.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webFailSoftRescueQuerySorter.trace\", ignore);"));
    }
}
