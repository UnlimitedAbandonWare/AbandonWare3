package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUiSendMessageContractTest {

    @Test
    void normalChatStartsStreamAndUpdatesConsoleBeforeSyncFallback() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int payload = source.indexOf("const payload = {");
        int firstStream = source.indexOf("await streamChat(payload, loaderId);", payload);
        int syncFallback = source.indexOf("const res = await apiCall(\"/api/chat\"", payload);

        assertTrue(payload > 0, "sendMessage payload block should exist");
        assertTrue(firstStream > payload, "sendMessage should call streamChat for normal chat");
        assertTrue(syncFallback > firstStream, "sync /api/chat should remain only after stream path/fallback");

        String beforeFirstStream = source.substring(payload, firstStream);
        assertTrue(beforeFirstStream.contains("updateOrchestrationSignalBar({"),
                "RAG console should switch out of idle before network wait");
        assertFalse(beforeFirstStream.contains("if (dom.useRag?.checked)"),
                "streamChat must not be gated behind the RAG checkbox");
    }

    @Test
    void sendMessageFinallyReEnablesInputWithExecutableStatements() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int payload = source.indexOf("const payload = {");
        int finallyBlock = source.indexOf("} finally {", payload);
        int stopButton = source.indexOf("if (dom.stopBtn)", finallyBlock);
        String block = source.substring(finallyBlock, stopButton);

        assertTrue(Pattern.compile("(?m)^\\s*dom\\.messageInput\\.disabled\\s*=\\s*false;")
                .matcher(block)
                .find(), "message input must be re-enabled by executable code");
        assertTrue(Pattern.compile("(?m)^\\s*dom\\.messageInput\\.focus\\(\\);")
                .matcher(block)
                .find(), "message input focus must be restored by executable code");
    }
}
