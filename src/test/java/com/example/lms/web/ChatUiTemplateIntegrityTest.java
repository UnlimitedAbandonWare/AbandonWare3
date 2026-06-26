package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUiTemplateIntegrityTest {

    @Test
    void activeChatUiCopyDoesNotExposeMojibakeOrMalformedControls() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String activeUi = html.substring(html.indexOf("<nav class=\"app-menu-bar\""), html.indexOf("<!-- === Scripts"));

        assertTrue(activeUi.contains("질문 분석, 검색, 재랭킹, 증거 게이트, 회복 경로"));
        assertTrue(activeUi.contains("Self-Ask, Anchor Compression, MoE routing, trace breadcrumbs를 한 화면에서 검증합니다."));
        assertTrue(activeUi.contains("운영 안정성 관점에서 Provider Guard, Trace, Fail-soft 구조를 정리해줘"));

        for (String token : new String[]{"?/button>", "理", "吏", "寃", "濡", "蹂", "媛", "鍮", "?꾩", "?댁", "?좏"}) {
            assertFalse(activeUi.contains(token), "active chat UI still contains mojibake token: " + token);
        }
    }
}
