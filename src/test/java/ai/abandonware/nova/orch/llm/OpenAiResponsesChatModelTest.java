package ai.abandonware.nova.orch.llm;

import com.example.lms.search.TraceStore;
import com.example.lms.test.SecretFixtures;
import com.example.lms.trace.SafeRedactor;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesChatModelTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void routeFailureMessageUsesStableReasonInsteadOfExceptionClassName() {
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                "http://127.0.0.1:1/v1",
                "test-api-key",
                "gpt-5-mini",
                1_000L);

        ChatResponse response = model.chat(List.of(UserMessage.from("hello")));
        String text = response.aiMessage().text();

        assertTrue(text.contains("code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), text);
        assertTrue(text.contains("error: responses-route-error"), text);
        assertFalse(text.contains("WebClientRequestException"), text);
        assertFalse(text.contains("ConnectException"), text);
        assertFalse(text.contains("127.0.0.1"), text);
    }

    @Test
    void placeholderApiKeyReturnsExpectedFailureBeforeNetworkPath() {
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                "http://127.0.0.1:1/v1",
                "sk-local",
                "gpt-5-mini",
                1_000L);

        ChatResponse response = model.chat(List.of(UserMessage.from("hello")));
        String text = response.aiMessage().text();

        assertTrue(text.contains("code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), text);
        assertTrue(text.contains("actionTaken: ROUTE_RESPONSES(no_api_key)"), text);
        assertFalse(text.contains("responses-route-error"), text);
        assertFalse(text.contains("ConnectException"), text);
        assertFalse(text.contains("127.0.0.1"), text);
    }

    @Test
    void responsesRouteDoesNotUseNoopOnErrorResume() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java"));

        assertFalse(source.contains(".onErrorResume(e -> Mono.error(e))"));
        assertFalse(source.contains("import reactor.core.publisher.Mono;"));
    }

    @Test
    void malformedResponsesJsonWritesRedactedParseBreadcrumb() throws Exception {
        String body = "{\"output_text\":\"" + SecretFixtures.openAiKey() + "\"";
        HttpServer server = startResponsesServer(body);
        try {
            OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "gpt-5-mini",
                    1_000L);

            ChatResponse response = model.chat(List.of(UserMessage.from("hello")));

            assertTrue(response.aiMessage().text().contains("(empty responses output)"));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.failed")));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.emptyOutput")));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.bodyPresent")));
            assertTrue(TraceStore.get("llm.responses.parse.bodyHash").equals(SafeRedactor.hashValue(body)));
            assertTrue(TraceStore.get("llm.responses.parse.bodyLength").equals(body.length()));
            assertTrue(TraceStore.get("llm.responses.parse.reason").equals("invalid_response_json"));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.invalid.suppressed")));
            assertFalse(TraceStore.getAll().containsValue(body));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatCompletionsCompatibleChoicesResponseExtractsAssistantText() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"compat hello\"}}]}";
        HttpServer server = startResponsesServer(body);
        try {
            OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "gpt-5-mini",
                    1_000L);

            ChatResponse response = model.chat(List.of(UserMessage.from("hello")));

            assertTrue(response.aiMessage().text().contains("compat hello"));
            assertTrue("CHAT_COMPAT".equals(TraceStore.get("responsesModel.format")));
            assertFalse(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.emptyOutput")));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startResponsesServer(String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] bytes = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
