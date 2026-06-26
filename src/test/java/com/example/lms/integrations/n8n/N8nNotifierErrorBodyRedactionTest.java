package com.example.lms.integrations.n8n;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class N8nNotifierErrorBodyRedactionTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void errorResponseBodyIsLoggedAsHashOnly() throws Exception {
        String errorBody = "{\"error\":\"" + com.example.lms.test.SecretFixtures.openAiKey() + "\"}";
        HttpServer server = errorServer(errorBody);
        Logger logger = (Logger) LoggerFactory.getLogger(N8nNotifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            N8nNotifier notifier = new N8nNotifier(WebClient.builder(), new N8nProps());

            notifier.notify(endpoint(server), Map.of("status", "done"));

            String rendered = waitForLogs(appender);
            assertThat(rendered)
                    .doesNotContain(errorBody)
                    .doesNotContain("" + com.example.lms.test.SecretFixtures.openAiKey() + "")
                    .contains("bodyHash=")
                    .contains("bodyLength=");
        } finally {
            logger.detachAppender(appender);
            server.stop(0);
        }
    }

    @Test
    void invalidCallbackUrlIsSkippedWithoutLoggingRawUrl() {
        String callbackUrl = "file:///C:/secret/callback?token=raw-callback-secret";
        Logger logger = (Logger) LoggerFactory.getLogger(N8nNotifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            N8nNotifier notifier = new N8nNotifier(WebClient.builder(), new N8nProps());

            notifier.notify(callbackUrl, Map.of("status", "done"));

            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertThat(rendered)
                    .contains("invalid_callback_url")
                    .contains("callbackHash=")
                    .contains("callbackLength=")
                    .doesNotContain(callbackUrl)
                    .doesNotContain("raw-callback-secret");
            assertThat(TraceStore.get("n8n.callback.skipped")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("n8n.callback.skipped.reason")).isEqualTo("invalid_callback_url");
            assertThat(String.valueOf(TraceStore.getAll()))
                    .doesNotContain(callbackUrl)
                    .doesNotContain("raw-callback-secret");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void malformedCallbackUrlLeavesRedactedTraceBreadcrumb() {
        String callbackUrl = "http://[raw-callback-secret";
        N8nNotifier notifier = new N8nNotifier(WebClient.builder(), new N8nProps());

        notifier.notify(callbackUrl, Map.of("status", "done"));

        assertThat(TraceStore.get("n8n.callback.suppressed.callbackUri")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("n8n.callback.suppressed.callbackUri.errorType")).isEqualTo("invalid_url");
        assertThat(String.valueOf(TraceStore.getAll()))
                .doesNotContain(callbackUrl)
                .doesNotContain("raw-callback-secret");
    }

    private static HttpServer errorServer(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/notify", exchange -> {
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
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/notify";
    }

    private static String waitForLogs(ListAppender<ILoggingEvent> appender) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            if (!appender.list.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }
}
