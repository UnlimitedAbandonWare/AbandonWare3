package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainStateFrontendContractTest {

    @Test
    void brainStateRendererUsesTextNodesAndRedactedDiagnosticsOnly() throws Exception {
        String js = read("main/resources/static/js/brain-state-ui.js");

        assertTrue(js.contains("textContent"));
        assertTrue(js.contains("replaceChildren"));
        assertTrue(js.contains("sourceSummaries"));
        assertTrue(js.contains("recentChanges"));
        assertTrue(js.contains("anchorMap"));
        assertTrue(js.contains("queryTime"));
        assertFalse(js.contains("innerHTML"));
        assertFalse(js.contains("insertAdjacentHTML"));
        assertFalse(js.contains("Authorization"));
        assertFalse(js.contains("ownerToken"));
        assertFalse(js.contains("apiKey"));
        assertFalse(js.contains("clientSecret"));
        assertFalse(js.contains("sourceText"));
        assertFalse(js.contains("/api/admin/vector/brain/ingest"));
        assertFalse(js.contains("/api/admin/vector/brain/infer"));
    }

    @Test
    void brainStateQueryTimeShowsFallbackReasonAndFailureClass() throws Exception {
        String js = read("main/resources/static/js/brain-state-ui.js");

        assertTrue(js.contains("queryTime.fallbackUsed ? \"fallback\" : \"direct\""));
        assertTrue(js.contains("failure=${safe(queryTime.failureClass, \"-\")}"));
        assertTrue(js.contains("disabled=${safe(queryTime.disabledReason, \"-\")}"));
        assertFalse(js.contains("rawQuery"));
        assertFalse(js.contains("sourceText"));
    }

    @Test
    void chatAndAdminPagesLoadPinnedBrainStateAssets() throws Exception {
        String chat = read("main/resources/templates/chat-ui.html");
        String page = read("main/resources/templates/brain-state.html");
        String dashboard = read("main/resources/templates/dashboard.html");
        String vector = read("main/resources/templates/vector-diagnostics.html");

        assertTrue(chat.contains("cytoscape@3.33.4"));
        assertTrue(page.contains("cytoscape@3.33.4"));
        assertTrue(chat.contains("/js/brain-state-ui.js"));
        assertTrue(page.contains("/js/brain-state-ui.js"));
        assertTrue(chat.contains("data-brain-state-root"));
        assertTrue(page.contains("data-brain-state-root"));
        assertTrue(page.contains("data-brain-list=\"sources\""));
        assertTrue(page.contains("data-brain-list=\"recent\""));
        assertTrue(page.contains("data-brain-list=\"anchor-map\""));
        assertTrue(page.contains("data-brain-list=\"query-time\""));
        assertTrue(chat.contains("data-menu-action=\"open-brain-state\""));
        assertTrue(dashboard.contains("/admin/brain-state"));
        assertTrue(vector.contains("/admin/brain-state"));
    }

    @Test
    void chatDispatchesOnlySafeBrainStateRefreshSignals() throws Exception {
        String js = read("main/resources/static/js/chat.js");

        assertTrue(js.contains("dispatchBrainStateSignal('session'"));
        assertTrue(js.contains("dispatchBrainStateSignal('answer'"));
        assertTrue(js.contains("new CustomEvent(`brain-state:${name}`"));
        assertFalse(js.contains("brain-state:prompt"));
        assertFalse(js.contains("brain-state:raw"));
    }

    @Test
    void embeddedBrainStatePanelDoesNotCollapseChatConversationViewport() throws Exception {
        String css = read("main/resources/static/css/chat-style.css").replace("\r\n", "\n");

        assertTrue(css.contains(".chat-area-wrapper > .brain-state-panel--compact"),
                "chat-embedded Brain State must have a chat-surface-specific compact rule");
        assertTrue(css.contains(".chat-area-wrapper > .brain-state-panel--compact .brain-state-main"),
                "chat-embedded Brain State graph body must be reduced so it cannot push messages away");
        assertTrue(css.contains("#chatWindow {\n    flex: 1 1"),
                "chatWindow must keep a flexible conversation viewport");
        assertTrue(css.contains("min-height: clamp("),
                "chatWindow must keep enough height for conversation text");
        assertTrue(css.contains("overflow: auto"),
                "chatWindow or compact Brain State must scroll instead of expanding over the conversation");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
