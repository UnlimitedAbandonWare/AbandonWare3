package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UawAutolearnStrictRequestAspectTest {

    @Test
    void optionalTemperatureParseCatchUsesSuppressionBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawAutolearnStrictRequestAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"temperature.parse\");"));
    }

    @Test
    void failClosedStrictRequestRecordsBlockedReasonBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawAutolearnStrictRequestAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("TraceStore.put(\"uaw.strictRequest.blocked\""),
                "strict UAW fail-closed paths should expose a stable blocked reason trace key");
    }
}
