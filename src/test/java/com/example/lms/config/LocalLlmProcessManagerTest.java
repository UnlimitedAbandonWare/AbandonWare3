package com.example.lms.config;

import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLlmProcessManagerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void generatedWindowsCommandSetsOllamaHostBeforeServe() {
        String command = LocalLlmProcessManager.generatedWindowsStartCommand("127.0.0.1:11435");

        assertEquals("start \"Ollama 127.0.0.1:11435\" cmd.exe /k \"set OLLAMA_HOST=127.0.0.1:11435&& ollama serve\"",
                command);
        assertTrue(command.contains("OLLAMA_HOST=127.0.0.1:11435"));
        assertTrue(command.contains("ollama serve"));
    }

    @Test
    void normalizesHttpUrlToOllamaHostEnvValue() {
        assertEquals("127.0.0.1:11435",
                LocalLlmProcessManager.normalizeOllamaHostForEnv("http://127.0.0.1:11435/api/embed"));
    }

    @Test
    void generatedWindowsCommandDropsUrlUserInfoFromOllamaHost() {
        String command = LocalLlmProcessManager.generatedWindowsStartCommand(
                "http://user:secret@127.0.0.1:11435/api/embed");

        assertTrue(command.contains("OLLAMA_HOST=127.0.0.1:11435&& ollama serve"));
        assertThat(command)
                .doesNotContain("user")
                .doesNotContain("secret")
                .doesNotContain("@");
    }

    @Test
    void generatedWindowsCommandFallsBackWhenOllamaHostContainsShellMetacharacters() {
        String command = LocalLlmProcessManager.generatedWindowsStartCommand(
                "127.0.0.1:11435 & calc");

        assertTrue(command.contains("OLLAMA_HOST=127.0.0.1:11435&& ollama serve"));
        assertThat(command).doesNotContain("calc");
    }

    @Test
    void beanFactoryPostProcessorRunsEarlyGateWhenDisabledWithoutNetworkCall() {
        LocalLlmProcessManager manager = new LocalLlmProcessManager(new MockEnvironment()
                .withProperty("local-llm.enabled", "false")
                .withProperty("local-llm.autostart", "true"));

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        assertTrue(manager.isRunning());
        assertEquals(Boolean.FALSE, TraceStore.get("localLlm.startup.enabled"));
        assertEquals(Boolean.TRUE, TraceStore.get("localLlm.startup.autostart"));
        assertEquals("skipped", TraceStore.get("localLlm.startup.status"));
        assertEquals("disabled_or_autostart_false", TraceStore.get("localLlm.startup.reason"));
        assertEquals("127.0.0.1:11435", TraceStore.get("localLlm.startup.host"));
        assertTrue(String.valueOf(TraceStore.get("localLlm.startup.hostHash")).startsWith("hash:"));
        assertEquals("127.0.0.1:11435", TraceStore.get("localLlm.startup.healthUrlHost"));
        assertTrue(String.valueOf(TraceStore.get("localLlm.startup.healthUrlHash")).startsWith("hash:"));
        assertEquals(Boolean.FALSE, TraceStore.get("localLlm.warmup.enabled"));
    }

    @Test
    void defaultConstructedBeanFactoryPostProcessorCanSkipWhenAutostartIsFalse() {
        LocalLlmProcessManager manager = new LocalLlmProcessManager();
        manager.setEnvironment(new MockEnvironment()
                .withProperty("local-llm.enabled", "false")
                .withProperty("local-llm.autostart", "false"));

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        assertTrue(manager.isRunning());
    }

    @Test
    void warmupPostErrorDoesNotExposeRawResponseBody() throws Exception {
        String errorBody = "{\"error\":\"failed api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + "\"}";
        HttpServer server = errorServer(errorBody);
        try {
            LocalLlmProcessManager manager = new LocalLlmProcessManager();
            ReflectionTestUtils.setField(manager, "ollamaHost", endpoint(server));

            Throwable thrown = catchThrowable(() -> ReflectionTestUtils.invokeMethod(
                    manager,
                    "postJson",
                    "/api/embed",
                    Map.of("model", "test-model"),
                    1000L));

            String rendered = renderThrowable(thrown);
            assertThat(rendered)
                    .doesNotContain(errorBody)
                    .doesNotContain("" + com.example.lms.test.SecretFixtures.openAiKey() + "")
                    .contains("bodyHash=")
                    .contains("bodyLength=");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startupFailureShortErrDoesNotExposeRawThrowableTypeOrMessage() {
        String rendered = ReflectionTestUtils.invokeMethod(
                LocalLlmProcessManager.class,
                "shortErr",
                new IllegalStateException("failed ownerToken=fake-token"));

        assertThat(rendered)
                .contains("startup_failure")
                .contains("messageHash=")
                .contains("messageLength=")
                .doesNotContain("IllegalStateException")
                .doesNotContain("ownerToken=fake-token")
                .doesNotContain("failed ownerToken");
    }

    @Test
    void warmupSuccessLogsDoNotWriteRawModelIdentifiers() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/LocalLlmProcessManager.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("step=pull status=ok model={}")
                .doesNotContain("step=show status=ok model={}")
                .doesNotContain("step=embed status=ok model={}");
        assertThat(source)
                .contains("step=pull status=ok modelHash={} modelLength={}")
                .contains("step=show status=ok modelHash={} modelLength={}")
                .contains("step=embed status=ok modelHash={} modelLength={} targetDim={} returnedDim={}")
                .contains("SafeRedactor.hashValue(model)");
    }

    @Test
    void startupLogsDoNotWriteRawHealthUrlOrHostAuthority() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/LocalLlmProcessManager.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("healthUrl={} host={}")
                .doesNotContain("status=healthy healthUrl={} timeoutMs={}")
                .doesNotContain("safeUrl(effectiveHealthCheckUrl())")
                .doesNotContain("normalizeOllamaHostForEnv(ollamaHost), hasText(startCommand)")
                .doesNotContain("normalizeOllamaHostForEnv(ollamaHost), shortErr(e)")
                .doesNotContain("POST \" + safeUrl(url)");
        assertThat(source)
                .contains("healthUrlHost={} healthUrlHash={} healthUrlLength={} host={} hostHash={}")
                .contains("status=healthy healthUrlHost={} healthUrlHash={} healthUrlLength={} timeoutMs={}")
                .contains("safeUrlHost(effectiveHealthCheckUrl())")
                .contains("safeUrlHash(effectiveHealthCheckUrl())")
                .contains("safeUrlLength(effectiveHealthCheckUrl())")
                .contains("safeOllamaHostForLog(ollamaHost), safeOllamaHostHash(ollamaHost), hasText(startCommand)")
                .contains("safeOllamaHostForLog(ollamaHost), safeOllamaHostHash(ollamaHost), shortErr(e)")
                .contains("POST \" + safeUrlDiagnostic(url)");
    }

    @Test
    void suppressedStartupHelpersLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/LocalLlmProcessManager.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("traceSuppressed(\"ollama.start\", ex);")
                .contains("traceSuppressed(\"ollama.unexpected\", e);")
                .contains("traceSuppressed(\"ollama.isServiceRunning\", e);")
                .contains("traceSuppressed(\"ollama.warmup\", e);")
                .contains("traceSuppressed(\"ollama.hostUri\", ignore);")
                .contains("traceSuppressed(\"ollama.urlHost\", ignore);")
                .contains("TraceStore.put(\"localLlm.suppressed.\" + safeStage, true);");
    }

    @Test
    void suppressedTraceIncludesSafeAggregateStageAndErrorType() {
        String secret = com.example.lms.test.SecretFixtures.openAiKey();

        ReflectionTestUtils.invokeMethod(LocalLlmProcessManager.class,
                "traceSuppressed",
                "ollama.hostUri " + secret,
                new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("localLlm.suppressed.stage");
        assertThat(String.valueOf(safeStage)).startsWith("hash:");
        assertEquals(Boolean.TRUE, TraceStore.get("localLlm.suppressed." + safeStage));
        assertEquals("local_llm_suppressed", TraceStore.get("localLlm.suppressed.errorType"));
        assertEquals("local_llm_suppressed",
                TraceStore.get("localLlm.suppressed." + safeStage + ".errorType"));
        assertThat(TraceStore.getAll().toString()).doesNotContain(secret);
    }

    private static HttpServer errorServer(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/embed", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    private static String renderThrowable(Throwable thrown) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            out.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return out.toString();
    }
}
