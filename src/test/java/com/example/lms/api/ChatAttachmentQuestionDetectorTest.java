package com.example.lms.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAttachmentQuestionDetectorTest {

    @Test
    void detectsAttachmentLanguageAndFileExtensions() {
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("Please summarize the uploaded document"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("첨부 파일 확인해줘"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("read report.pdf"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("hello there"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("   "));
    }
}
