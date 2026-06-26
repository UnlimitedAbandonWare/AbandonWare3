package com.example.lms.config;

import com.abandonware.ai.agent.tool.AgentToolInvoker;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.request.ToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolOpsConfigContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentToolOpsConfig.class);

    @Test
    void configRegistersFirstWaveOpsToolsWithoutLegacySideEffectTools() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentToolInvoker.class);
            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertThat(registry.get("verify.contract")).isPresent();
            assertThat(registry.get("ops.snapshot")).isPresent();
            assertThat(registry.get("repo.scan")).isPresent();
            assertThat(registry.get("source.map")).isPresent();
            assertThat(registry.get("trace.snapshot")).isPresent();
            assertThat(registry.get("kg.query")).isPresent();
            assertThat(registry.get("moe.strategy.query")).isPresent();
            assertThat(registry.get("config.inspect")).isPresent();
            assertThat(registry.get("debug.trace.lookup")).isPresent();
            assertThat(registry.get("db_evidence_scan")).isPresent();
            assertThat(registry.get("failure.pattern.scan")).isPresent();
            assertThat(registry.get("failure.pattern.recall")).isPresent();
            assertThat(registry.get("failure.pattern.record")).isPresent();
            assertThat(registry.get("message.send")).isEmpty();
        });
    }

    @Test
    void dbEvidenceScanCanBeInvokedThroughRegisteredOpsInvoker() {
        contextRunner.run(context -> {
            AgentToolInvoker invoker = context.getBean(AgentToolInvoker.class);

            Map<String, Object> result = invoker.invoke(
                    "db_evidence_scan",
                    Map.of(),
                    new ToolContext("ops-config-test-session", null),
                    true);

            assertThat(result.get("ok")).isEqualTo(true);
            assertThat(result.get("toolId")).isEqualTo("db_evidence_scan");
            assertThat(result.toString()).contains("schemaVersion={present=true");
            assertThat(result.toString()).contains("hash12=");
            assertThat(result.toString()).doesNotContain("agent.db_evidence_scan.v1");
            assertThat(result.toString()).doesNotContain("C:\\AbandonWare");
        });
    }
}
