package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaNativeChatModelTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void qwenThinkingModelSendsThinkFalseAndReturnsContent() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(requestBody);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl,
                    "qwen3:8b",
                    Duration.ofSeconds(2),
                    32,
                    0.1d);

            ChatResponse response = model.chat(List.of(UserMessage.from("private prompt must stay out of trace")));

            assertEquals("OK", response.aiMessage().text());
            assertTrue(requestBody.get().contains("\"think\":false"), requestBody.get());
            assertTrue(requestBody.get().contains("\"num_predict\":32"), requestBody.get());
            assertTrue(requestBody.get().contains("\"temperature\":0.1"), requestBody.get());
            assertEquals(Boolean.TRUE, TraceStore.get("llm.ollamaNative.thinkDisabled"));

            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("private prompt"), trace);
            assertFalse(trace.contains("qwen3:8b"), trace);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dynamicFactoryRoutesLoopbackQwenThinkingModelsThroughNativeAdapter() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/DynamicChatModelFactory.java"));

        assertTrue(source.contains("new OllamaNativeChatModel("));
        assertTrue(source.contains("shouldUseOllamaNativeThinkFalse"));
        assertTrue(source.contains("llm.ollamaNative.route"));
    }

    private static HttpServer startServer(AtomicReference<String> requestBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            requestBody.set(new String(bytes, StandardCharsets.UTF_8));
            byte[] response = "{\"message\":{\"content\":\"OK\"},\"done_reason\":\"stop\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }
}
