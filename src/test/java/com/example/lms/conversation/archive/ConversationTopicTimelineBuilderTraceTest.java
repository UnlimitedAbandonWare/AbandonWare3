package com.example.lms.conversation.archive;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationTopicTimelineBuilderTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void malformedLinkHostParseLeavesRedactedSuppressionBreadcrumb() throws Exception {
        String raw = "https://[ownerToken-secret";
        Method method = ConversationTopicTimelineBuilder.class.getDeclaredMethod("firstHost", String.class);
        method.setAccessible(true);

        assertNull(method.invoke(null, raw));

        assertEquals(Boolean.TRUE, TraceStore.get("conversation.archive.suppressed.conversationArchive.firstHost"));
        assertEquals("conversationArchive.firstHost", TraceStore.get("conversation.archive.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("conversation.archive.suppressed.errorType"));
        assertEquals("IllegalArgumentException",
                TraceStore.get("conversation.archive.suppressed.conversationArchive.firstHost.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }
}
