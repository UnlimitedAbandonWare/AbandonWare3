package com.example.lms.api;

import java.util.Locale;

final class ChatAttachmentQuestionDetector {

    private ChatAttachmentQuestionDetector() {
    }

    static boolean looksLikeAttachmentQuestion(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String s = msg.toLowerCase(Locale.ROOT);
        return s.contains("attachment")
                || s.contains("uploaded")
                || s.contains("upload")
                || s.contains("file")
                || s.contains("document")
                || s.contains("pdf")
                || s.contains("zip")
                || s.contains("첨부")
                || s.contains("파일")
                || s.contains("문서")
                || s.matches(".*\\.(txt|md|pdf|doc|docx|xlsx|csv|json|xml|zip|jpg|jpeg|png|gif)\\b.*");
    }
}
